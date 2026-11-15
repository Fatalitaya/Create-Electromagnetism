var mc=Java.loadClass('net.minecraft.client.Minecraft').getInstance();mc.setScreen(null);
var File=Java.loadClass('java.io.File');Java.loadClass('net.minecraft.client.Screenshot').grab(new File('.'),mc.getMainRenderTarget(),function(c){});
JSON.stringify({hit:String(mc.hitResult.getBlockPos()),block:String(mc.level.getBlockState(mc.hitResult.getBlockPos()))});
