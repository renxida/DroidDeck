/* ts runtime: payload = TypeScript script body; stripped with Sucrase on device, then run as function(root, G). */
window.G = GCore;
window.__renderImpl = function (src, root) {
  performance.mark('ts-start');
  const js = Sucrase.transform(src, { transforms: ['typescript'], disableESTransforms: true }).code;
  performance.measure('ts-transform', 'ts-start');
  root.replaceChildren();
  new Function('root', 'G', js)(root, G);
};
