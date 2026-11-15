// AutoJS client: inspect the existing client without changing the world or UI.
var mc=Java.loadClass('net.minecraft.client.Minecraft').getInstance();
var overlay=Java.loadClass('com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer');
JSON.stringify({screen:String(mc.screen),worldLoaded:mc.level!==null,hovered:String(overlay.lastHovered),hoverTicks:overlay.hoverTicks,spaceWidth:mc.font.width(' ')});
