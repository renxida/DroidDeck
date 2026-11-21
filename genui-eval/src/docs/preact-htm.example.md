## Example
```js
function App() {
  const [d, setD] = useState([{ label: 'Mon', value: 6 }, { label: 'Tue', value: 8 }]);
  return html`<div class="stack"><div class="spread"><div class="title">Steps</div>
    <button class="btn small" data-testid="add" onClick=${() => setD(d.map((x, i) => i === 1 ? { ...x, value: x.value + 1 } : x))}>+1k</button></div>
    <${G.Bar} data=${d} target=${7} unit="k"/></div>`;
}
return App;
```
