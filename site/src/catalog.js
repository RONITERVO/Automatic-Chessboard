export const PARTS = Object.freeze({
  deck: {
    color: "#f7f4ec",
    name: "Printed 37.5 millimetre board tiles",
    url: "https://github.com/RONITERVO/Automatic-Chessboard/tree/main/hardware",
  },
  pieces: {
    color: "#d9c7a6",
    name: "Magnet-ready printed chess pieces",
    url: "https://github.com/RONITERVO/Automatic-Chessboard/tree/main/hardware",
  },
  pieceMagnets: {
    color: "#73d7ff",
    name: "Five by two millimetre neodymium piece magnets",
    url: "https://www.supermagnete.fi/disc-magnets-neodymium/disc-magnet-5mm-2mm_S-05-02-N",
  },
  reedSwitches: {
    color: "#dff7ff",
    name: "Littelfuse MDSR-4 normally-open reed switches",
    url: "https://www.digikey.be/en/products/detail/littelfuse-inc/MDSR-4-12-23/200302",
  },
  multiplexers: {
    color: "#1279d3",
    name: "WAVGAT CD74HC4067 multiplexer modules",
    url: "https://www.aliexpress.com/item/32848578672.html",
  },
  controller: {
    color: "#1689d8",
    name: "Official classic Arduino Nano ATmega328P",
    url: "https://store.arduino.cc/products/arduino-nano",
  },
  drivers: {
    color: "#111820",
    name: "Pololu A4988 Black Edition stepper drivers",
    url: "https://www.pololu.com/product/2128",
  },
  driverCaps: {
    color: "#23313b",
    name: "Panasonic one hundred microfarad fifty volt capacitors",
    url: "https://www.digikey.fi/en/products/detail/panasonic-electronic-components/EEU-FR1H101/3561182",
  },
  motors: {
    color: "#30363d",
    name: "StepperOnline 17HS13-0404S1 NEMA seventeen motors",
    url: "https://www.omc-stepperonline.com/nema-17-bipolar-1-8deg-26ncm-36-8oz-in-0-4a-12v-42x42x34mm-4-wires-17hs13-0404s1",
  },
  frame: {
    color: "#20262c",
    name: "OpenBuilds V-Slot twenty by twenty rail",
    url: "https://us.openbuilds.com/v-slot-20x20-linear-rail.html",
  },
  motion: {
    color: "#15191d",
    name: "Two millimetre pitch GT2 belt and twenty-tooth pulleys",
    url: "https://www.omc-stepperonline.com/2gt-timing-belt",
  },
  magnet: {
    color: "#15191d",
    name: "Landa H2520 twenty-four volt electromagnet",
    url: "https://zslanda.en.made-in-china.com/product/rtypxJCcOURO/China-Landa-H2520-Round-Holding-Electromagnet-100n-25mm-Diameter-20mm-Height-24V-12V-DC-Cast-Iron-Mini-Magnet.html",
  },
  magnetDriver: {
    color: "#24313a",
    name: "onsemi TIP120G magnet transistor",
    url: "https://www.digikey.fi/en/products/detail/onsemi/TIP120G/920293",
  },
  bluetooth: {
    color: "#1aa773",
    name: "DSD TECH SH-HC-08 BLE carrier",
    url: "https://www.deshide.com/product-details_1663307.html",
  },
  display: {
    color: "#1957d2",
    name: "Sixteen by two PCF8574 I2C display",
    url: "https://www.addicore.com/products/1602-16x2-character-lcd-with-i2c-backpack",
  },
  endstops: {
    color: "#232a31",
    name: "Normally-open lever microswitches",
    url: "https://www.digikey.fi/en/products/detail/omron-electronics-inc-emc-div/D2F-01L/83262",
  },
  pcb: {
    color: "#146b46",
    name: "Documented controller wiring board",
    url: "https://github.com/RONITERVO/Automatic-Chessboard/tree/main/hardware",
  },
  logicPower: {
    color: "#165e4a",
    name: "Pololu D24V25F5 fixed five volt regulator",
    url: "https://www.pololu.com/product/2850",
  },
  reverseProtection: {
    color: "#16714f",
    name: "Pololu four to seventy-five volt reverse protector",
    url: "https://www.pololu.com/product/5358",
  },
  fuse: {
    color: "#f4b942",
    name: "Blue Sea waterproof ATO ATC fuse holder",
    url: "https://www.bluesea.com/products/5065/",
  },
  cutoff: {
    color: "#e5383b",
    name: "Omron A22E latching emergency cutoff",
    url: "https://www.newark.com/omron-sti/a22elm24a02/switch-emergency-stop-dpst-nc/dp/08R7299",
  },
  powerSupply: {
    color: "#20262d",
    name: "Mean Well GST90A24-P1M enclosed twenty-four volt supply",
    url: "https://www.meanwell-web.com/content/files/pdfs/productPdfs/MW/GST90A/GST90A-spec.pdf",
  },
});

export const PURCHASABLE_PART_IDS = Object.freeze(Object.keys(PARTS));
