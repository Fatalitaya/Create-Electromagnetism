var BasicRegistries=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var BasicResource=Java.loadClass('net.minecraft.resources.ResourceLocation');
var basicKinds=['creative_battery','creative_resistor','cut_off_switch','ammeter','voltmeter'];
JSON.stringify(basicKinds.map(function(kind){var key=BasicResource.parse('electroenergetics:'+kind);return {id:String(key),exists:BasicRegistries.BLOCK.containsKey(key),state:String(BasicRegistries.BLOCK.get(key).defaultBlockState())};}));
