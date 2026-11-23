package net.minecraft.client.renderer;
public class EntityRenderer {
    public boolean renderHand = true;
    public void renderWorldPass(int pass, float partialTicks, long finishTimeNano) {
        long x = finishTimeNano + pass;
        if (this.renderHand) {
            this.renderHand(partialTicks, pass);
        }
    }
    private void renderHand(float partialTicks, int pass) {}
}
