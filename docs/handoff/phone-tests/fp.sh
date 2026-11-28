D=/data/user/0/dev.renxida.kestrel/files/logs; S=$D/$(ls -t $D | head -1); echo $(basename $S)
/usr/bin/python3 - $S <<'P'
import sys,os,re,statistics as st
S=sys.argv[1]; W=120000
fr=[];t0=None
for l in open(S+'/frames.csv'):
    if l.startswith('#'):
        m=re.search(r't0_wall_ms=(\d+)',l); t0=int(m[1]) if m else t0; continue
    if l[0]=='t': continue
    a=l.strip().split(',')
    if len(a)>=2: fr.append((t0+int(a[0]),int(a[1])/1000))
end=fr[-1][0]; lo=end-W
ft=sorted(x for t,x in fr if t>lo); n=len(ft); p=lambda q: ft[min(n-1,int(q*n))]
print('frames: n=%d fps=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.0f >50ms/min=%.1f'%(n,n/(W/1000),p(.5),p(.95),p(.99),ft[-1],sum(x>50 for x in ft)/(W/60000)))
def csv(f):
    L=open(S+'/'+f).read().splitlines(); h=L[0].split(','); return h,[r.split(',') for r in L[1:] if r and r[0].isdigit()]
h,R=csv('processes.csv'); it=h.index('t_wall_ms');ic=h.index('cpu_pct');inm=h.index('name')
by={}
for r in R:
    t=int(r[it])
    if lo-30000<t<=end+5000 and r[ic]: by.setdefault(t,{})[r[inm]]=float(r[ic])
if by:
    tot=[sum(v.values()) for v in by.values()]
    names={}
    for v in by.values():
        for k,x in v.items(): names.setdefault(k,[]).append(x)
    top=sorted(((st.mean(x),k) for k,x in names.items()),reverse=True)[:5]
    print('cpu%%: total %.0f over %d samples; top %s'%(st.mean(tot),len(tot),', '.join('%s %.0f'%(k,m) for m,k in top)))
cl=[];hr=[];tb=[]
for l in open(S+'/clocks.log'):
    m=re.match(r'(\d\d):(\d\d):(\d\d) (.*)',l)
    if not m: continue
    cl.append(l)
fl=cl[-60:]
pol={}
for l in fl:
    for k,a,b in re.findall(r'(p\d) (\d+)/(\d+)',l): pol.setdefault(k,[]).append(int(a))
    m=re.search(r'headroom ([\d.]+)',l); hr.append(float(m[1])) if m else None
    m=re.search(r'battery ([\d.]+) C',l); tb.append(float(m[1])) if m else None
print('clocks(last 2 min): '+' '.join('%s %.0f'%(k,st.mean(v)) for k,v in sorted(pol.items()))+(' | headroom %.2f->%.2f mean %.2f'%(hr[0],hr[-1],st.mean(hr)) if hr else '')+(' | batt %.1f->%.1f C'%(tb[0],tb[-1]) if tb else ''))
h,R=csv('power.csv'); it=h.index('t_wall_ms'); ip=h.index('power_mw'); ipl=h.index('plugged')
pw=[(r[ip],r[ipl]) for r in R if lo<int(r[it])<=end+5000]
un=[float(a) for a,b in pw if b=='none' and a]
print('power: %d rows, plugged=%s%s'%(len(pw),sorted({b for a,b in pw}),(' unplugged mean %.0f mW'%st.mean(un)) if un else ''))
P
