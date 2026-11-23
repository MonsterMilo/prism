package net.minecraft.client.network;
import net.minecraft.network.NetworkManager;
public class NetHandlerPlayClient {
    private final NetworkManager manager;
    public NetHandlerPlayClient(NetworkManager manager) { this.manager = manager; }
    public NetworkManager getNetworkManager() { return manager; }
}
