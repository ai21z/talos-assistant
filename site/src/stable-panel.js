export function reservePanelSpace(panel, variants) {
  const frame = document.createElement("div");
  frame.className = "stable-panel";
  panel.before(frame);
  frame.append(panel);
  for (const variant of variants) {
    for (const element of [variant, ...variant.querySelectorAll("*")]) {
      for (const attribute of [...element.attributes]) {
        if (/^(?:id|name|role|tabindex|for)$|^(?:aria-|data-)/.test(attribute.name)) {
          element.removeAttribute(attribute.name);
        }
      }
    }
    variant.classList.add("panel-size-reserve");
    variant.setAttribute("aria-hidden", "true");
    variant.inert = true;
    frame.append(variant);
  }
}
