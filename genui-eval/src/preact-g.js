/* preact-htm runtime: payload = JS function body that returns a component. In scope: html, h, useState, useEffect, useRef, useMemo, G. */
(function () {
  const { h, render } = preact, H = preactHooks, html = htm.bind(h);
  const wrap = (fn, attr, cbName) => (p) => h('div', {
    class: 'chart ' + (p.class || ''), style: p.style,
    onClick: (e) => { const el = e.target.closest('[' + attr + ']'); if (el && p[cbName]) p[cbName](+el.getAttribute(attr)); },
    dangerouslySetInnerHTML: { __html: fn(p.data, p) },
  });
  window.G = Object.assign({}, GCore, {
    Bar: wrap(GCore.bar, 'data-bar', 'onBar'),
    Line: wrap(GCore.line, 'data-x', 'onPoint'),
    Donut: wrap(GCore.donut, 'data-slice', 'onSlice'),
    Spark: (p) => h('span', { dangerouslySetInnerHTML: { __html: GCore.spark(p.data, p) } }),
  });
  window.__renderImpl = function (src, root) {
    render(null, root);
    const App = new Function('html', 'h', 'useState', 'useEffect', 'useRef', 'useMemo', 'G', src)(html, h, H.useState, H.useEffect, H.useRef, H.useMemo, G);
    render(h(App, null), root);
  };
})();
