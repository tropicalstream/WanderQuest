#!/usr/bin/env python3
"""WanderQuest — parametric mythological-creature sprite generator.

Builds 23 NEW creatures (joining the 7 hand-drawn enemies for a 30-strong
bestiary) from distinct archetypes — serpents, winged beasts, ogres,
multi-head hounds, spirits — each with a global-myth palette. Output: one
PNG per creature in each theme dir.
"""
import os, math, colorsys
from PIL import Image

N = 18  # grid size

def grid():
    return [['.' for _ in range(N)] for _ in range(N)]

def disk(g, cx, cy, r, ch='A'):
    for y in range(N):
        for x in range(N):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                g[y][x] = ch

def ellipse(g, cx, cy, rx, ry, ch='A'):
    for y in range(N):
        for x in range(N):
            if rx > 0 and ry > 0 and ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0:
                g[y][x] = ch

def rect(g, x0, y0, x1, y1, ch='A'):
    for y in range(max(0, y0), min(N, y1 + 1)):
        for x in range(max(0, x0), min(N, x1 + 1)):
            g[y][x] = ch

def line(g, x0, y0, x1, y1, ch='A'):
    dx, dy = abs(x1 - x0), abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    while True:
        if 0 <= x0 < N and 0 <= y0 < N:
            g[y0][x0] = ch
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy; x0 += sx
        if e2 < dx:
            err += dx; y0 += sy

def tri(g, p0, p1, p2, ch='A'):
    pts = [p0, p1, p2]
    ys = [p[1] for p in pts]
    for y in range(max(0, min(ys)), min(N, max(ys) + 1)):
        xs = []
        for i in range(3):
            a, b = pts[i], pts[(i + 1) % 3]
            if (a[1] <= y < b[1]) or (b[1] <= y < a[1]):
                t = (y - a[1]) / (b[1] - a[1])
                xs.append(a[0] + t * (b[0] - a[0]))
        if len(xs) >= 2:
            for x in range(max(0, int(min(xs))), min(N, int(max(xs)) + 1)):
                g[y][x] = ch

def wing(g, base_x, base_y, side, ch='B'):
    # side -1 left, +1 right; a swept bat/feather wing
    tipx = base_x + side * 7
    tri(g, (base_x, base_y - 3), (tipx, base_y - 5), (base_x, base_y + 3), ch)
    tri(g, (base_x, base_y + 1), (tipx, base_y + 1), (base_x, base_y + 4), ch)

def horn(g, x, y, side, ch='C'):
    line(g, x, y, x + side * 2, y - 4, ch)
    line(g, x + side, y - 1, x + side * 2, y - 4, ch)

def eyes(g, pts, white='W', pupil='S'):
    for (x, y) in pts:
        if 0 <= x < N and 0 <= y < N:
            g[y][x] = white
            if x + 1 < N:
                g[y][x] = white

def outline(g, ch='O'):
    for y in range(N):
        for x in range(N):
            if g[y][x] != '.':
                continue
            for dy, dx in ((1,0),(-1,0),(0,1),(0,-1)):
                ny, nx = y+dy, x+dx
                if 0 <= ny < N and 0 <= nx < N and g[ny][nx] not in ('.', 'O'):
                    g[y][x] = ch
                    break

def to_strings(g):
    return "\n".join("".join(r) for r in g)

# ---- archetype builders (return char grid) ----

def a_orb():  # wisp / spirit
    g = grid(); cx = cy = N//2
    disk(g, cx, cy, 4, 'A')
    disk(g, cx-1, cy-1, 1, 'W')
    for ang in range(0, 360, 60):
        x = int(cx + 7*math.cos(math.radians(ang)))
        y = int(cy + 7*math.sin(math.radians(ang)))
        if 0 <= x < N and 0 <= y < N: g[y][x] = 'C'
    outline(g); return g

def a_biped(horns=0, cap=False, club=False, big=False):
    g = grid(); cx = N//2
    hr = 3 if big else 2
    hy = 4
    disk(g, cx, hy, hr, 'A')              # head
    rect(g, cx-3 if big else cx-2, hy+hr, cx+(3 if big else 2), N-4, 'A')  # torso
    line(g, cx-(3 if big else 2), hy+hr+1, cx-5, hy+hr+4, 'A')  # arms
    line(g, cx+(3 if big else 2), hy+hr+1, cx+5, hy+hr+4, 'A')
    line(g, cx-2, N-4, cx-2, N-2, 'A')    # legs
    line(g, cx+2, N-4, cx+2, N-2, 'A')
    if cap:
        tri(g, (cx-3, hy-1), (cx+3, hy-1), (cx, hy-5), 'C')
    for i in range(horns):
        horn(g, cx + (-2 if i == 0 else 2), hy-hr+1, -1 if i == 0 else 1)
    if club:
        line(g, cx+5, hy+hr+4, cx+6, hy-1, 'B'); disk(g, cx+6, hy-2, 2, 'B')
    eyes(g, [(cx-1, hy), (cx+1, hy)])
    g[hy][cx-1] = 'S'; g[hy][cx+1] = 'S'
    outline(g); return g

def a_beast(heads=1, antlers=False, wings=False, tail=True, winglow=False):
    g = grid(); by = N//2 + 1
    ellipse(g, N//2, by, 5, 3, 'A')       # body
    for lx in (N//2-3, N//2+3):           # legs
        line(g, lx, by+2, lx, N-2, 'A')
    if tail:
        line(g, N//2+5, by, N-2, by-3, 'A')
    hx0 = N//2 - 5
    for h in range(heads):
        hx = hx0 + h*5 if heads > 1 else N//2 - 4
        disk(g, hx, by-2, 2, 'A')
        g[by-2][hx] = 'S'
        if antlers:
            horn(g, hx-1, by-4, -1, 'C'); horn(g, hx+1, by-4, 1, 'C')
    if wings:
        wing(g, N//2-1, by-1, -1, 'B'); wing(g, N//2+1, by-1, 1, 'B')
    outline(g); return g

def a_serpent(humanoid=False, heads=1):
    g = grid()
    # S-curve coils
    pts = [(4,13),(7,11),(10,12),(12,9),(10,6),(7,7)]
    for i in range(len(pts)-1):
        line(g, pts[i][0], pts[i][1], pts[i+1][0], pts[i+1][1], 'A')
    for (x,y) in pts:
        disk(g, x, y, 2, 'A')
    if humanoid:
        disk(g, 7, 4, 2, 'A'); rect(g, 5, 6, 9, 8, 'A')
        eyes(g, [(6,4),(8,4)]); g[4][6]='S'; g[4][8]='S'
    else:
        for h in range(heads):
            hx = 7 + h*3
            disk(g, hx, 5, 1, 'A'); g[5][hx]='S'
    outline(g); return g

def a_winged(beak=False):  # harpy / avian
    g = grid(); cx = N//2
    ellipse(g, cx, 9, 3, 4, 'A')          # body
    disk(g, cx, 4, 2, 'A')                # head
    if beak:
        tri(g, (cx, 4), (cx, 6), (cx-4, 5), 'C')
    wing(g, cx-2, 8, -1, 'B'); wing(g, cx+2, 8, 1, 'B')
    line(g, cx-1, 13, cx-1, 16, 'A'); line(g, cx+1, 13, cx+1, 16, 'A')
    eyes(g, [(cx-1,4),(cx+1,4)]); g[4][cx-1]='S'; g[4][cx+1]='S'
    outline(g); return g

def a_drake():  # dragon / manticore
    g = grid()
    ellipse(g, 9, 10, 5, 3, 'A')          # body
    disk(g, 5, 7, 2, 'A')                 # head
    tri(g, (5,7),(5,9),(1,8),'C')         # snout
    horn(g, 4, 5, -1, 'C')
    wing(g, 10, 8, 1, 'B');
    tri(g, (11,8),(17,4),(13,11),'B')
    line(g, 13, 11, 17, 14, 'A')          # tail
    disk(g, 17, 14, 1, 'C')               # tail spike
    for lx in (7, 11):
        line(g, lx, 12, lx, 16, 'A')
    g[7][5]='S'
    outline(g); return g

def a_golem():
    g = grid()
    rect(g, 6, 3, 11, 6, 'A')             # head
    rect(g, 5, 7, 12, 13, 'A')            # torso
    rect(g, 3, 7, 4, 12, 'A')             # arms
    rect(g, 13, 7, 14, 12, 'A')
    rect(g, 6, 14, 8, 16, 'A'); rect(g, 9, 14, 11, 16, 'A')
    g[5][7]='C'; g[5][10]='C'             # glowing eyes
    rect(g, 7, 9, 10, 10, 'B')            # core
    outline(g); return g

# ---- the new roster (key -> (builder, palette ABCWS) ) ----
# palette: A primary, B secondary, C accent (W=white,S=dark auto)
P = {}
NEW = {}
def add(key, g, A, B, C):
    NEW[key] = to_strings(g)
    P[key] = {"A": A, "B": B, "C": C}

def a_pixie():
    g = a_biped()
    wing(g, 7, 8, -1, 'B'); wing(g, 10, 8, 1, 'B')
    outline(g); return g

add("wisp",     a_orb(), (150,210,255), (90,150,220), (210,240,255))
add("pixie",    a_pixie(), (190,240,170), (150,200,255), (255,190,225))
add("kappa",    a_biped(), (110,190,130), (90,140,90), (220,180,90))
add("kobold",   a_biped(horns=1), (170,120,80), (120,80,60), (255,200,90))
add("gremlin",  a_beast(), (130,160,110), (90,110,80), (255,120,90))
add("bakeneko", a_beast(heads=1, tail=True), (160,120,200), (110,80,150), (255,210,120))
add("harpy",    a_winged(beak=True), (180,140,90), (120,90,60), (255,220,120))
add("redcap",   a_biped(cap=True, club=True), (180,150,120), (120,90,70), (220,50,50))
add("gargoyle", a_biped(horns=2, big=True), (140,145,160), (100,105,120), (90,90,100))
add("jackal",   a_beast(), (70,70,90), (45,45,60), (230,190,80))
add("draugr",   a_biped(), (110,130,110), (70,90,75), (160,255,160))
add("naga",     a_serpent(humanoid=True), (90,180,140), (60,130,100), (255,210,120))
add("banshee",  a_biped(), (200,215,235), (150,170,210), (180,210,255))
add("minotaur", a_biped(horns=2, big=True), (150,100,60), (110,75,45), (240,220,200))
add("oni",      a_biped(horns=2, club=True, big=True), (210,90,80), (150,55,50), (255,220,90))
add("golem",    a_golem(), (155,150,140), (110,105,95), (120,200,255))
add("cyclops",  a_biped(big=True, club=True), (210,180,140), (150,120,90), (220,60,60))
add("wendigo",  a_beast(antlers=True), (120,120,130), (80,80,90), (230,230,240))
add("cerberus", a_beast(heads=3), (60,55,70), (40,35,50), (255,90,60))
add("manticore",a_drake(), (200,90,70), (150,60,50), (255,210,120))
add("djinn",    a_serpent(humanoid=True), (150,110,220), (100,70,170), (200,170,255))
add("dragon",   a_drake(), (90,170,90), (60,120,60), (255,210,120))
add("hydra",    a_serpent(heads=3), (80,170,140), (55,120,100), (255,120,120))

# ---- render with theme palettes (mirrors gen_sprites) ----
THEME_OUTLINE = {"fantasy": (12,10,16), "anime": (58,38,92), "western": (54,36,22)}
OUT = "/sessions/wonderful-wizardly-knuth/mnt/Projects/new-x3-app/app/src/main/assets/sprites"

def theme_color(rgb, theme):
    r,g,b = [v/255 for v in rgb]
    h,s,v = colorsys.rgb_to_hsv(r,g,b)
    if theme == "anime":
        v = min(1.0, v*1.15+0.10); s *= 0.75
        r,g,b = colorsys.hsv_to_rgb(h,s,v); r,g,b = r*0.82+0.18,g*0.82+0.18,b*0.82+0.18
    elif theme == "western":
        s *= 0.45; v *= 0.92
        r,g,b = colorsys.hsv_to_rgb(h,s,v); r,g,b = r*0.78+0.20,g*0.78+0.14,b*0.78+0.06
    return (int(r*255),int(g*255),int(b*255),255)

count = 0
for theme in ("fantasy","anime","western"):
    d = os.path.join(OUT, theme); os.makedirs(d, exist_ok=True)
    for key, gridstr in NEW.items():
        rows = gridstr.split("\n"); w = max(len(r) for r in rows); rows = [r.ljust(w,'.') for r in rows]
        base = P[key]
        pal = {
            "O": THEME_OUTLINE[theme],
            "A": theme_color(base["A"], theme),
            "B": theme_color(base["B"], theme),
            "C": theme_color(base["C"], theme),
            "W": (255,255,255,255) if theme != "western" else (245,235,210,255),
            "S": tuple(int(c*0.45) for c in theme_color(base["B"],theme)[:3]) + (255,),
        }
        img = Image.new("RGBA",(w,len(rows)),(0,0,0,0)); px = img.load()
        for y,row in enumerate(rows):
            for x,ch in enumerate(row):
                if ch != '.': px[x,y] = pal[ch]
        img.save(os.path.join(d, f"{key}.png")); count += 1

print("wrote", count, "creature images;", len(NEW), "unique creatures")
print("roster:", ", ".join(NEW.keys()))
