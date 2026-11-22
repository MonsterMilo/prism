//! Thin helpers over raw JNI and the few JVMTI functions Prism needs.

use jni_sys::*;
use std::ffi::{c_char, c_void, CStr, CString};
use std::ptr::null_mut;

/// A JNIEnv for the current thread.
#[derive(Clone, Copy)]
pub struct Env(pub *mut JNIEnv);

macro_rules! jni {
    ($env:expr, $f:ident $(, $a:expr)*) => {
        unsafe { ((**$env.0).$f.expect(stringify!($f)))($env.0 $(, $a)*) }
    };
}

fn c(s: &str) -> CString {
    CString::new(s).expect("nul in string")
}

impl Env {
    /// Clears a pending Java exception. Returns its toString() if there was one.
    pub fn take_exception(&self) -> Option<String> {
        if jni!(self, ExceptionCheck) == JNI_FALSE {
            return None;
        }
        let t = jni!(self, ExceptionOccurred);
        jni!(self, ExceptionClear);
        if t.is_null() {
            return Some("unknown exception".into());
        }
        let text = self.call_to_string(t);
        self.delete_local(t);
        Some(text.unwrap_or_else(|| "exception".into()))
    }

    fn ok(&self) -> Result<(), String> {
        match self.take_exception() {
            Some(e) => Err(e),
            None => Ok(()),
        }
    }

    pub fn delete_local(&self, o: jobject) {
        if !o.is_null() {
            jni!(self, DeleteLocalRef, o);
        }
    }

    pub fn global(&self, o: jobject) -> jobject {
        jni!(self, NewGlobalRef, o)
    }

    pub fn delete_global(&self, o: jobject) {
        if !o.is_null() {
            jni!(self, DeleteGlobalRef, o);
        }
    }

    pub fn find_class(&self, name: &str) -> Result<jclass, String> {
        let n = c(name);
        let cls = jni!(self, FindClass, n.as_ptr());
        self.ok()?;
        if cls.is_null() {
            return Err(format!("class {name} not found"));
        }
        Ok(cls)
    }

    pub fn method(&self, cls: jclass, name: &str, sig: &str) -> Result<jmethodID, String> {
        let (n, s) = (c(name), c(sig));
        let m = jni!(self, GetMethodID, cls, n.as_ptr(), s.as_ptr());
        self.ok()?;
        if m.is_null() {
            return Err(format!("method {name}{sig} not found"));
        }
        Ok(m)
    }

    /// A static method, or None if the class doesn't have it (no exception left behind).
    pub fn static_method(&self, cls: jclass, name: &str, sig: &str) -> Option<jmethodID> {
        let (n, s) = (c(name), c(sig));
        let m = jni!(self, GetStaticMethodID, cls, n.as_ptr(), s.as_ptr());
        if self.take_exception().is_some() || m.is_null() {
            None
        } else {
            Some(m)
        }
    }

    pub fn call_object(
        &self,
        obj: jobject,
        m: jmethodID,
        args: &[jvalue],
    ) -> Result<jobject, String> {
        let r = jni!(self, CallObjectMethodA, obj, m, args.as_ptr());
        self.ok()?;
        Ok(r)
    }

    pub fn call_void(&self, obj: jobject, m: jmethodID, args: &[jvalue]) -> Result<(), String> {
        jni!(self, CallVoidMethodA, obj, m, args.as_ptr());
        self.ok()
    }

    pub fn call_static_object(
        &self,
        cls: jclass,
        m: jmethodID,
        args: &[jvalue],
    ) -> Result<jobject, String> {
        let r = jni!(self, CallStaticObjectMethodA, cls, m, args.as_ptr());
        self.ok()?;
        Ok(r)
    }

    pub fn call_static_void(
        &self,
        cls: jclass,
        m: jmethodID,
        args: &[jvalue],
    ) -> Result<(), String> {
        jni!(self, CallStaticVoidMethodA, cls, m, args.as_ptr());
        self.ok()
    }

    pub fn new_object(
        &self,
        cls: jclass,
        ctor_sig: &str,
        args: &[jvalue],
    ) -> Result<jobject, String> {
        let m = self.method(cls, "<init>", ctor_sig)?;
        let o = jni!(self, NewObjectA, cls, m, args.as_ptr());
        self.ok()?;
        Ok(o)
    }

    /// A Java string from Rust text (via UTF-16, so any path survives).
    pub fn string(&self, s: &str) -> jstring {
        let w: Vec<u16> = s.encode_utf16().collect();
        jni!(self, NewString, w.as_ptr(), w.len() as jsize)
    }

    pub fn rust_string(&self, s: jstring) -> String {
        if s.is_null() {
            return String::new();
        }
        let len = jni!(self, GetStringLength, s);
        let chars = jni!(self, GetStringChars, s, null_mut());
        if chars.is_null() {
            return String::new();
        }
        let out =
            String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(chars, len as usize) });
        jni!(self, ReleaseStringChars, s, chars);
        out
    }

    pub fn call_to_string(&self, o: jobject) -> Option<String> {
        let obj = jni!(self, FindClass, c("java/lang/Object").as_ptr());
        let m = jni!(
            self,
            GetMethodID,
            obj,
            c("toString").as_ptr(),
            c("()Ljava/lang/String;").as_ptr()
        );
        let s = jni!(self, CallObjectMethod, o, m);
        if jni!(self, ExceptionCheck) != JNI_FALSE {
            jni!(self, ExceptionClear);
            return None;
        }
        let r = self.rust_string(s);
        self.delete_local(s);
        self.delete_local(obj);
        Some(r)
    }

    /// Class.forName(name, false, loader), or None.
    pub fn load_class(&self, loader: jobject, dotted: &str) -> Option<jclass> {
        let class = self.find_class("java/lang/Class").ok()?;
        let for_name = self.static_method(
            class,
            "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
        )?;
        let name = self.string(dotted);
        let r = self.call_static_object(
            class,
            for_name,
            &[
                jvalue { l: name },
                jvalue { z: JNI_FALSE },
                jvalue { l: loader },
            ],
        );
        self.delete_local(name);
        self.delete_local(class);
        match r {
            Ok(c) if !c.is_null() => Some(c),
            _ => None,
        }
    }

    pub fn array_len(&self, a: jarray) -> jsize {
        jni!(self, GetArrayLength, a)
    }

    pub fn array_get(&self, a: jobjectArray, i: jsize) -> jobject {
        jni!(self, GetObjectArrayElement, a, i)
    }

    pub fn new_object_array(&self, cls: jclass, len: jsize, first: jobject) -> jobjectArray {
        jni!(self, NewObjectArray, len, cls, first)
    }

    pub fn byte_array(&self, a: jbyteArray) -> Vec<u8> {
        let len = self.array_len(a) as usize;
        let mut out = vec![0u8; len];
        jni!(
            self,
            GetByteArrayRegion,
            a,
            0,
            len as jsize,
            out.as_mut_ptr() as *mut jbyte
        );
        out
    }

    pub fn register_natives(
        &self,
        cls: jclass,
        methods: &[(&str, &str, *mut c_void)],
    ) -> Result<(), String> {
        let names: Vec<(CString, CString)> = methods.iter().map(|(n, s, _)| (c(n), c(s))).collect();
        let table: Vec<JNINativeMethod> = methods
            .iter()
            .zip(names.iter())
            .map(|((_, _, f), (n, s))| JNINativeMethod {
                name: n.as_ptr() as *mut c_char,
                signature: s.as_ptr() as *mut c_char,
                fnPtr: *f,
            })
            .collect();
        let r = jni!(
            self,
            RegisterNatives,
            cls,
            table.as_ptr(),
            table.len() as jint
        );
        self.ok()?;
        if r != 0 {
            return Err(format!("RegisterNatives returned {r}"));
        }
        Ok(())
    }

    pub fn unregister_natives(&self, cls: jclass) {
        jni!(self, UnregisterNatives, cls);
        let _ = self.take_exception();
    }

    pub fn push_frame(&self, n: jint) -> bool {
        jni!(self, PushLocalFrame, n) == 0
    }

    pub fn pop_frame(&self) {
        jni!(self, PopLocalFrame, null_mut());
    }
}

// ---------------------------------------------------------------------------------- JavaVM

pub fn attach(vm: *mut JavaVM, name: &str) -> Option<Env> {
    let n = c(name);
    let mut args = JavaVMAttachArgs {
        version: JNI_VERSION_1_6,
        name: n.as_ptr() as *mut c_char,
        group: null_mut(),
    };
    let mut env: *mut c_void = null_mut();
    let r = unsafe {
        ((**vm).AttachCurrentThreadAsDaemon.unwrap())(
            vm,
            &mut env,
            &mut args as *mut _ as *mut c_void,
        )
    };
    if r == JNI_OK && !env.is_null() {
        Some(Env(env as *mut JNIEnv))
    } else {
        None
    }
}

pub fn detach(vm: *mut JavaVM) {
    unsafe {
        ((**vm).DetachCurrentThread.unwrap())(vm);
    }
}

/// The JNIEnv of the calling thread if it is attached.
pub fn env_of(vm: *mut JavaVM) -> Option<Env> {
    let mut env: *mut c_void = null_mut();
    let r = unsafe { ((**vm).GetEnv.unwrap())(vm, &mut env, JNI_VERSION_1_6) };
    (r == JNI_OK && !env.is_null()).then(|| Env(env as *mut JNIEnv))
}

// ---------------------------------------------------------------------------------- JVMTI

const JVMTI_VERSION_1_2: jint = 0x3001_0200;

/// jvmtiEnv: a pointer to the JVMTI function table. Slots are the function numbers in
/// the JVMTI spec minus one (see jvmti.h, "/* 47 : Deallocate */" and so on).
#[derive(Clone, Copy)]
pub struct Jvmti(*mut *const *const c_void);

#[repr(C)]
struct ClassDefinition {
    klass: jclass,
    class_byte_count: jint,
    class_bytes: *const u8,
}

impl Jvmti {
    pub fn get(vm: *mut JavaVM) -> Option<Jvmti> {
        let mut env: *mut c_void = null_mut();
        let r = unsafe { ((**vm).GetEnv.unwrap())(vm, &mut env, JVMTI_VERSION_1_2) };
        (r == JNI_OK && !env.is_null()).then(|| Jvmti(env as *mut *const *const c_void))
    }

    fn slot(&self, number: usize) -> *const c_void {
        unsafe { *(*self.0).add(number - 1) }
    }

    fn dealloc(&self, p: *mut u8) {
        let f: extern "system" fn(Jvmti, *mut u8) -> jint =
            unsafe { std::mem::transmute(self.slot(47)) };
        f(*self, p);
    }

    /// Asks for can_redefine_classes (bit 9 of the capabilities). Returns whether it was granted.
    pub fn enable_redefine(&self) -> bool {
        let mut caps = [0u32; 4];
        let potential: extern "system" fn(Jvmti, *mut u32) -> jint =
            unsafe { std::mem::transmute(self.slot(140)) };
        if potential(*self, caps.as_mut_ptr()) != 0 || caps[0] & (1 << 9) == 0 {
            return false;
        }
        let want = [1u32 << 9, 0, 0, 0];
        let add: extern "system" fn(Jvmti, *const u32) -> jint =
            unsafe { std::mem::transmute(self.slot(142)) };
        add(*self, want.as_ptr()) == 0
    }

    pub fn redefine(&self, cls: jclass, bytes: &[u8]) -> jint {
        let def = ClassDefinition {
            klass: cls,
            class_byte_count: bytes.len() as jint,
            class_bytes: bytes.as_ptr(),
        };
        let f: extern "system" fn(Jvmti, jint, *const ClassDefinition) -> jint =
            unsafe { std::mem::transmute(self.slot(87)) };
        f(*self, 1, &def)
    }

    /// Calls `visit` with the loader of every loaded class whose signature is in `sigs`.
    /// Stops when `visit` returns true.
    pub fn for_class_loaders(
        &self,
        env: Env,
        sigs: &[&str],
        mut visit: impl FnMut(jclass, jobject) -> bool,
    ) {
        let mut count: jint = 0;
        let mut classes: *mut jclass = null_mut();
        let get_loaded: extern "system" fn(Jvmti, *mut jint, *mut *mut jclass) -> jint =
            unsafe { std::mem::transmute(self.slot(78)) };
        let get_sig: extern "system" fn(Jvmti, jclass, *mut *mut c_char, *mut *mut c_char) -> jint =
            unsafe { std::mem::transmute(self.slot(48)) };
        let get_loader: extern "system" fn(Jvmti, jclass, *mut jobject) -> jint =
            unsafe { std::mem::transmute(self.slot(57)) };
        if get_loaded(*self, &mut count, &mut classes) != 0 || classes.is_null() {
            return;
        }
        let list = unsafe { std::slice::from_raw_parts(classes, count as usize) };
        let mut done = false;
        for &cls in list {
            if !done {
                let mut sig: *mut c_char = null_mut();
                if get_sig(*self, cls, &mut sig, null_mut()) == 0 && !sig.is_null() {
                    let s = unsafe { CStr::from_ptr(sig) }
                        .to_string_lossy()
                        .into_owned();
                    self.dealloc(sig as *mut u8);
                    if sigs.contains(&s.as_str()) {
                        let mut loader: jobject = null_mut();
                        if get_loader(*self, cls, &mut loader) == 0 && !loader.is_null() {
                            done = visit(cls, loader);
                            env.delete_local(loader);
                        }
                    }
                }
            }
            env.delete_local(cls);
        }
        self.dealloc(classes as *mut u8);
    }

    pub fn dispose(&self) {
        let f: extern "system" fn(Jvmti) -> jint = unsafe { std::mem::transmute(self.slot(127)) };
        f(*self);
    }
}

unsafe impl Send for Jvmti {}
unsafe impl Sync for Jvmti {}
