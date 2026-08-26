import { useEffect, useMemo } from "react";
import * as THREE from "three";

function roundedRect(context, x, y, width, height, radius) {
  context.beginPath();
  context.roundRect(x, y, width, height, radius);
}

function fitFont(context, font, text, maxWidth, minimum = 20) {
  const match = font.match(/(\d+)px/);
  if (!match) return font;
  let size = Number(match[1]);
  context.font = font;
  while (size > minimum && context.measureText(String(text ?? "")).width > maxWidth) {
    size -= 2;
    context.font = font.replace(/\d+px/, `${size}px`);
  }
  return context.font;
}

export function usePanelTexture({
  lines,
  width = 1024,
  height = 320,
  background = "#07110d",
  border = "#4ef1a9",
  foreground = "#baffd9",
  accent = "#70f5bb",
  title = "",
  titleColor = accent,
  titleFont = "800 34px ui-monospace, SFMono-Regular, Consolas, monospace",
  font = "700 58px ui-monospace, SFMono-Regular, Consolas, monospace",
}) {
  const canvas = useMemo(() => {
    const element = document.createElement("canvas");
    element.width = width;
    element.height = height;
    return element;
  }, [height, width]);
  const texture = useMemo(() => {
    const next = new THREE.CanvasTexture(canvas);
    next.colorSpace = THREE.SRGBColorSpace;
    next.minFilter = THREE.LinearFilter;
    next.magFilter = THREE.LinearFilter;
    return next;
  }, [canvas]);

  useEffect(() => {
    const context = canvas.getContext("2d");
    context.clearRect(0, 0, width, height);
    roundedRect(context, 8, 8, width - 16, height - 16, 34);
    context.fillStyle = background;
    context.fill();
    context.strokeStyle = border;
    context.lineWidth = 7;
    context.stroke();

    let y = 72;
    if (title) {
      context.fillStyle = titleColor;
      context.font = fitFont(context, titleFont, title.toUpperCase(), width - 108, 18);
      context.textBaseline = "middle";
      context.fillText(title.toUpperCase(), 54, y);
      y += 62;
    }
    context.fillStyle = foreground;
    context.textBaseline = "middle";
    const lineHeight = Math.max(64, (height - y - 28) / Math.max(1, lines.length));
    lines.forEach((line, index) => {
      context.fillStyle = index === 0 ? accent : foreground;
      context.font = fitFont(context, font, line, width - 108, 18);
      context.fillText(String(line ?? ""), 54, y + index * lineHeight);
    });
    texture.needsUpdate = true;
  }, [accent, background, border, canvas, font, foreground, height, lines, texture, title, titleColor, titleFont, width]);

  useEffect(() => () => texture.dispose(), [texture]);
  return texture;
}

export function PanelFace({
  lines,
  title,
  size = [10, 3.2],
  colors = {},
  position = [0, 0, 0],
  rotation = [-Math.PI / 2, 0, 0],
  onClick,
  renderOrder = 8,
}) {
  const texture = usePanelTexture({ lines, title, ...colors });
  return (
    <mesh position={position} rotation={rotation} onClick={onClick} renderOrder={renderOrder}>
      <planeGeometry args={size} />
      <meshBasicMaterial map={texture} transparent toneMapped={false} depthWrite={false} />
    </mesh>
  );
}
