package prism.core.hook;

import java.util.function.Predicate;

/**
 * One place in a game method where Prism calls in. The injected code only uses JDK classes:
 * {@code ((Predicate) System.getProperties().get(key)).test(new Object[]{args...})}, and for a
 * cancelable hook a {@code true} result returns from the method. That way the game's class
 * loader never has to see Prism's classes.
 */
public final class Hook {
    public enum At { HEAD, TAIL, BEFORE_CALL }

    final String id;
    final String owner;          // internal name, e.g. net/minecraft/client/Minecraft
    final String[] names;        // method name per mapping: {mcp, srg}
    final String desc;
    final At at;
    final String callOwner;      // BEFORE_CALL: the called method
    final String[] callNames;
    final String callDesc;
    final int[] argLocals;       // local variable slots passed to the handler
    final boolean cancelable;
    final Predicate<Object[]> handler;
    /** Only exists on some clients (e.g. Forge's classes): no warning when missing. */
    boolean optional;

    private Hook(String id, String owner, String[] names, String desc, At at, String callOwner,
                 String[] callNames, String callDesc, int[] argLocals, boolean cancelable,
                 Predicate<Object[]> handler) {
        this.id = id;
        this.owner = owner;
        this.names = names;
        this.desc = desc;
        this.at = at;
        this.callOwner = callOwner;
        this.callNames = callNames;
        this.callDesc = callDesc;
        this.argLocals = argLocals;
        this.cancelable = cancelable;
        this.handler = handler;
    }

    static Hook head(String id, String owner, String mcp, String srg, String desc, int[] args,
                     boolean cancelable, Predicate<Object[]> handler) {
        return new Hook(id, owner, new String[] {mcp, srg}, desc, At.HEAD, null, null, null, args, cancelable, handler);
    }

    static Hook tail(String id, String owner, String mcp, String srg, String desc, int[] args,
                     Predicate<Object[]> handler) {
        return new Hook(id, owner, new String[] {mcp, srg}, desc, At.TAIL, null, null, null, args, false, handler);
    }

    static Hook beforeCall(String id, String owner, String mcp, String srg, String desc,
                           String callOwner, String callMcp, String callSrg, String callDesc, int[] args,
                           Predicate<Object[]> handler) {
        return new Hook(id, owner, new String[] {mcp, srg}, desc, At.BEFORE_CALL, callOwner,
                new String[] {callMcp, callSrg}, callDesc, args, false, handler);
    }

    Hook optional() {
        optional = true;
        return this;
    }

    String key() { return "prism.hook." + id; }

    String name(String mappings) { return pick(names, mappings); }

    String callName(String mappings) { return pick(callNames, mappings); }

    private static String pick(String[] n, String mappings) {
        return "srg".equals(mappings) ? n[1] : n[0];
    }
}
