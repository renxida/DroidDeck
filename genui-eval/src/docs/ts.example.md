## Example
```ts
const d: { label: string; value: number }[] = [{ label: 'Mon', value: 6 }, { label: 'Tue', value: 8 }];
root.innerHTML = `<div class="stack"><div class="spread"><div class="title">Steps</div><button class="btn small" data-testid="add">+1k</button></div><div id="c"></div></div>`;
const c = root.querySelector<HTMLDivElement>('#c')!;
const draw = (): void => { c.innerHTML = G.bar(d, { target: 7, unit: 'k' }); };
root.querySelector<HTMLButtonElement>('[data-testid=add]')!.onclick = () => { d[1].value++; draw(); };
draw();
```
