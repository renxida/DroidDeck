const init = [["Passport",1],["Charger",1],["Toothbrush",0],["Rain jacket",0],["Headphones",0],["Snacks",0]].map(([n, d]) => ({ n, d: !!d }));
function App() {
  const [items, setItems] = useState(init);
  const n = items.filter(i => i.d).length;
  return html`<div class="stack">
    <div class="spread"><div class="title">Weekend trip packing</div><span class="sub num" data-testid="progress-label">${n}/${items.length} packed</span></div>
    <div class="progress"><i style=${{ width: n / items.length * 100 + '%' }}></i></div>
    <div class="list">${items.map((it, i) => html`
      <label data-testid=${'item-' + i} style="display:flex;align-items:center;gap:12px;cursor:pointer;min-height:28px">
        <input type="checkbox" class="check" checked=${it.d} onChange=${e => setItems(items.map((x, j) => j === i ? { ...x, d: e.target.checked } : x))}/>
        <span style=${{ color: it.d ? 'var(--onSurface3)' : 'var(--onSurface)', textDecoration: it.d ? 'line-through' : 'none' }}>${it.n}</span>
      </label>`)}</div>
  </div>`;
}
return App;
