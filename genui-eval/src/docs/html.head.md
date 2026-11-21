# Runtime: html
Write one HTML fragment: optional `<style>`, markup, then `<script>`. Scripts run in order as `function(root, G)`: `root` is the container element (query with root.querySelector), `G` the helper object below. No imports, no external URLs. Scope CSS under a class on your top element.
