## Example
```html
<div class="x stack"><div class="spread"><div class="title">Steps</div><button class="btn small" data-testid="add">+1k</button></div><div id="c"></div></div>
<script>
const d=[{label:'Mon',value:6},{label:'Tue',value:8}];
const draw=()=>root.querySelector('#c').innerHTML=G.bar(d,{target:7,unit:'k'});
root.querySelector('[data-testid=add]').onclick=()=>{d[1].value++;draw()};draw();
</script>
```
