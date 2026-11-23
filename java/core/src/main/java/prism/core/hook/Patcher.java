package prism.core.hook;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds hook calls to a class's bytecode. Reports per hook whether it was placed. */
final class Patcher {
    /** Result per hook, same order as the input list: null = placed, else why not. */
    static final class Result {
        byte[] bytes;
        String[] problems;
    }

    static Result patch(byte[] original, List<Hook> hooks, String mappings) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, ClassReader.EXPAND_FRAMES);
        Result r = new Result();
        r.problems = new String[hooks.size()];
        boolean changed = false;
        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            MethodNode m = find(cn, h.name(mappings), h.desc);
            if (m == null) {
                r.problems[i] = "method " + h.name(mappings) + h.desc + " not found";
                continue;
            }
            if (h.cancelable && (h.at != Hook.At.HEAD || Type.getReturnType(m.desc) != Type.VOID_TYPE)) {
                r.problems[i] = "cancelable hooks must be at the head of a void method";
                continue;
            }
            int placed = place(m, h, mappings);
            if (placed == 0) r.problems[i] = "no injection point in " + m.name;
            else changed = true;
        }
        if (changed) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            r.bytes = cw.toByteArray();
        }
        return r;
    }

    private static MethodNode find(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    private static int place(MethodNode m, Hook h, String mappings) {
        int count = 0;
        switch (h.at) {
            case HEAD: {
                AbstractInsnNode first = m.instructions.getFirst();
                m.instructions.insertBefore(first, call(m, h, first));
                count = 1;
                break;
            }
            case TAIL:
                for (AbstractInsnNode n : m.instructions.toArray()) {
                    int op = n.getOpcode();
                    if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                        m.instructions.insertBefore(n, call(m, h, null));
                        count++;
                    }
                }
                break;
            case BEFORE_CALL:
                String callName = h.callName(mappings);
                for (AbstractInsnNode n : m.instructions.toArray()) {
                    if (n instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode) n;
                        if (mi.owner.equals(h.callOwner) && mi.name.equals(callName) && mi.desc.equals(h.callDesc)) {
                            m.instructions.insertBefore(n, call(m, h, null));
                            count++;
                        }
                    }
                }
                break;
        }
        return count;
    }

    /**
     * ((Predicate) System.getProperties().get(key)).test(new Object[]{...})
     * then either pop, or (cancelable, HEAD only) "if true: return".
     */
    private static InsnList call(MethodNode m, Hook h, AbstractInsnNode next) {
        InsnList l = new InsnList();
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false));
        l.add(new LdcInsnNode(h.key()));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        l.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Predicate"));
        l.add(new IntInsnNode(Opcodes.BIPUSH, h.argLocals.length));
        l.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        Type[] params = Type.getArgumentTypes(m.desc);
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        for (int i = 0; i < h.argLocals.length; i++) {
            int slot = h.argLocals[i];
            Type t = typeOfSlot(params, isStatic, slot);
            l.add(new InsnNode(Opcodes.DUP));
            l.add(new IntInsnNode(Opcodes.BIPUSH, i));
            l.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            box(l, t);
            l.add(new InsnNode(Opcodes.AASTORE));
        }
        l.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true));
        if (!h.cancelable) {
            l.add(new InsnNode(Opcodes.POP));
            return l;
        }
        // if (!result) jump over the return
        LabelNode resume = existingLabelWithFrame(next);
        boolean needFrame = resume == null;
        if (needFrame) resume = new LabelNode();
        l.add(new JumpInsnNode(Opcodes.IFEQ, resume));
        l.add(new InsnNode(Opcodes.RETURN));   // cancelable hooks are only placed on void methods
        if (needFrame) {
            l.add(resume);
            l.add(new FrameNode(Opcodes.F_NEW, 0, null, 0, null));
            FrameNode f = (FrameNode) l.getLast();
            f.local = initialLocals(m, isStatic, h.owner);
            f.stack = new java.util.ArrayList<>();
        }
        return l;
    }

    /** The method starts with a label that already has a frame: jump there instead of adding one. */
    private static LabelNode existingLabelWithFrame(AbstractInsnNode first) {
        AbstractInsnNode n = first;
        LabelNode label = null;
        while (n != null && (n instanceof LabelNode || n.getType() == AbstractInsnNode.LINE)) {
            if (n instanceof LabelNode && label == null) label = (LabelNode) n;
            n = n.getNext();
        }
        return label != null && n instanceof FrameNode ? label : null;
    }

    private static List<Object> initialLocals(MethodNode m, boolean isStatic, String owner) {
        List<Object> locals = new java.util.ArrayList<>();
        if (!isStatic) locals.add(owner);
        for (Type t : Type.getArgumentTypes(m.desc)) {
            switch (t.getSort()) {
                case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                    locals.add(Opcodes.INTEGER); break;
                case Type.FLOAT: locals.add(Opcodes.FLOAT); break;
                case Type.LONG: locals.add(Opcodes.LONG); break;
                case Type.DOUBLE: locals.add(Opcodes.DOUBLE); break;
                default: locals.add(t.getInternalName());
            }
        }
        return locals;
    }

    private static Type typeOfSlot(Type[] params, boolean isStatic, int slot) {
        int s = isStatic ? 0 : 1;
        if (!isStatic && slot == 0) return Type.getObjectType("java/lang/Object");
        for (Type t : params) {
            if (s == slot) return t;
            s += t.getSize();
        }
        throw new IllegalArgumentException("slot " + slot + " is not a parameter");
    }

    private static void box(InsnList l, Type t) {
        String box;
        switch (t.getSort()) {
            case Type.BOOLEAN: box = "java/lang/Boolean"; break;
            case Type.BYTE: box = "java/lang/Byte"; break;
            case Type.CHAR: box = "java/lang/Character"; break;
            case Type.SHORT: box = "java/lang/Short"; break;
            case Type.INT: box = "java/lang/Integer"; break;
            case Type.FLOAT: box = "java/lang/Float"; break;
            case Type.LONG: box = "java/lang/Long"; break;
            case Type.DOUBLE: box = "java/lang/Double"; break;
            default: return;
        }
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box, "valueOf", "(" + t.getDescriptor() + ")L" + box + ";", false));
    }
}
