/* html runtime: payload = HTML fragment (<style>, markup, <script>). Scripts run in order as function(root, G). */
window.G = GCore;
window.__renderImpl = function (src, root) {
  const t = document.createElement('template');
  t.innerHTML = src;
  const scripts = [...t.content.querySelectorAll('script')].map((s) => { s.remove(); return s.textContent; });
  root.replaceChildren(t.content);
  scripts.forEach((code) => new Function('root', 'G', code)(root, G));
};
