#!/usr/bin/env python3
"""WanderQuest chiptune SFX generator -> res/raw/*.wav (22050 Hz mono 16-bit)."""
import numpy as np, wave, os

SR = 22050
OUT = "/sessions/wonderful-wizardly-knuth/mnt/Projects/new-x3-app/app/src/main/res/raw"
os.makedirs(OUT, exist_ok=True)

def t(ms): return np.linspace(0, ms / 1000.0, int(SR * ms / 1000.0), endpoint=False)

def env(n, a=0.005, r=0.6):
    """attack/release envelope over n samples; r = fraction of tail that decays."""
    e = np.ones(n)
    na = max(1, int(SR * a)); nr = max(1, int(n * r))
    e[:na] = np.linspace(0, 1, na)
    e[-nr:] *= np.linspace(1, 0, nr) ** 1.5
    return e

def square(f, ms, duty=0.5, vib=0.0):
    x = t(ms)
    ph = 2 * np.pi * f * x
    if vib: ph += vib * np.sin(2 * np.pi * 6 * x)
    return np.sign(np.sin(ph) - (duty - 0.5) * 2) * 0.5

def tri(f, ms):
    x = t(ms)
    return 2 * np.abs(2 * ((f * x) % 1) - 1) - 1

def saw(f, ms):
    x = t(ms)
    return 2 * ((f * x) % 1) - 1

def sine(f, ms):
    return np.sin(2 * np.pi * f * t(ms))

def sweep(f0, f1, ms, wave_fn=np.sin):
    x = t(ms)
    f = np.linspace(f0, f1, len(x))
    ph = 2 * np.pi * np.cumsum(f) / SR
    return wave_fn(ph)

def noise(ms):
    return np.random.uniform(-1, 1, int(SR * ms / 1000.0))

def seq(parts, gap_ms=0):
    g = np.zeros(int(SR * gap_ms / 1000.0))
    out = []
    for p in parts:
        out.append(p); out.append(g)
    return np.concatenate(out[:-1]) if out else np.zeros(0)

def mix(*sigs):
    n = max(len(s) for s in sigs)
    acc = np.zeros(n)
    for s in sigs: acc[:len(s)] += s
    return acc

def save(name, sig, vol=0.8):
    sig = np.asarray(sig, dtype=np.float64)
    peak = np.max(np.abs(sig)) or 1.0
    pcm = (sig / peak * vol * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())

N = lambda midi: 440.0 * 2 ** ((midi - 69) / 12.0)
rng = np.random.default_rng(42)
np.random.seed(42)

# --- UI ---
save("ui_move", square(1400, 36) * env(len(t(36)), r=0.8), 0.45)
save("ui_select", seq([square(880, 50) * env(len(t(50))), square(1320, 80) * env(len(t(80)))]), 0.5)
save("ui_back", seq([square(1100, 45) * env(len(t(45))), square(740, 75) * env(len(t(75)))]), 0.45)

# --- collection joy (variable-ratio reward tiers) ---
def arp(midis, ms_each, fn=square, vib=0.0):
    return seq([fn(N(m), ms_each, vib=vib) * env(len(t(ms_each)), r=0.7) if fn is square
                else fn(N(m), ms_each) * env(len(t(ms_each)), r=0.7) for m in midis])

save("collect_common", arp([84, 88, 91], 55), 0.55)
save("collect_uncommon", arp([84, 88, 91, 96], 55), 0.6)
save("collect_rare", mix(arp([84, 88, 91, 96, 100], 65), arp([72, 76, 79, 84, 88], 65) * 0.4), 0.65)
epic = mix(arp([79, 84, 88, 91, 96, 100, 103], 70), arp([67, 72, 76, 79, 84, 88, 91], 70) * 0.5)
save("collect_epic", mix(epic, np.concatenate([np.zeros(int(SR*0.12)), epic*0.35])), 0.7)
leg = mix(arp([72, 79, 84, 91, 96, 103, 108], 90, tri), arp([60, 67, 72, 79, 84, 91, 96], 90) * 0.5)
save("collect_legendary", mix(leg, np.concatenate([np.zeros(int(SR*0.16)), leg*0.4])), 0.75)
save("shiny_sparkle", mix(*[sine(f, 420) * env(len(t(420)), r=0.9) * v for f, v in
     [(2093, 0.5), (2637, 0.4), (3136, 0.35), (4186, 0.25)]]), 0.5)

# --- chest / shrine / progression ---
creak = sweep(160, 420, 240, lambda p: np.sign(np.sin(p))) * env(len(t(240)), r=0.5) * 0.4
coins = seq([square(rng.choice([1568, 1760, 2093, 2349]), 45) * env(len(t(45)), r=0.9) * 0.5 for _ in range(6)], 18)
save("chest_open", np.concatenate([creak, coins]), 0.6)
bell = lambda f, ms: mix(sine(f, ms), sine(f * 2.76, ms) * 0.4, sine(f * 5.4, ms) * 0.15) * env(len(t(ms)), r=0.92)
save("shrine_chime", seq([bell(1047, 500), bell(1568, 700)], 60), 0.6)
save("bank_coins", seq([square(rng.choice([1568, 1865, 2093]), 40) * env(len(t(40)), r=0.9) * 0.5 for _ in range(9)], 14), 0.55)
scale = [60, 62, 64, 65, 67, 69, 71, 72]
save("level_up", mix(arp(scale, 70, tri), arp([s + 12 for s in scale], 70) * 0.35), 0.7)
fanfare = seq([mix(square(N(m), 160), square(N(m + 4), 160) * 0.7, square(N(m + 7), 160) * 0.7) * env(len(t(160)), r=0.4)
               for m in [72, 72, 72, 77]], 25)
save("unlock_fanfare", fanfare, 0.7)
save("streak_chime", seq([bell(1319, 280), bell(1568, 280), bell(2093, 450)], 40), 0.6)
save("golden_start", mix(arp([76, 81, 85, 88, 93, 97], 95, tri), shiny := mix(*[sine(f, 600) * env(len(t(600)), r=0.95) * 0.2 for f in (2637, 3520)])), 0.65)
save("duel_start", seq([mix(square(N(57), 200), square(N(64), 200) * 0.8) * env(len(t(200)), r=0.3),
                        mix(square(N(60), 200), square(N(67), 200) * 0.8) * env(len(t(200)), r=0.3),
                        mix(square(N(64), 350), square(N(72), 350) * 0.8) * env(len(t(350)), r=0.6)], 30), 0.7)
save("duel_win", mix(arp([72, 76, 79, 84, 79, 84, 88, 96], 85, tri), arp([60, 64, 67, 72, 67, 72, 76, 84], 85) * 0.4), 0.75)
save("duel_lose", arp([64, 60, 57, 52], 220, tri), 0.6)

# --- the hunt: proximity + fear ---
save("geiger_tick", noise(9) * env(len(t(9)), a=0.0005, r=1.0), 0.5)
save("radar_ping", sweep(1500, 1150, 180) * env(len(t(180)), r=0.9), 0.45)
thump = lambda: mix(sweep(95, 42, 110) * env(len(t(110)), a=0.002, r=0.7), noise(40) * 0.12 * env(len(t(40)), r=1.0))
save("heartbeat", seq([thump(), thump() * 0.7], 90), 0.85)
growl = mix(saw(58, 700) * (0.7 + 0.3 * np.sin(2 * np.pi * 9 * t(700))), noise(700) * 0.25,
            saw(43, 700) * 0.6)
save("monster_growl", growl * env(len(growl), a=0.05, r=0.4), 0.7)
save("monster_near", mix(sweep(220, 180, 450, lambda p: 2*((p/(2*np.pi))%1)-1), noise(450)*0.2) * env(len(t(450)), a=0.02, r=0.5), 0.6)
# jump scare: dissonant cluster scream + noise blast, pitch falling
scream = mix(sweep(1860, 640, 850, lambda p: 2*((p/(2*np.pi))%1)-1),
             sweep(2480, 880, 850, lambda p: 2*((p/(2*np.pi))%1)-1) * 0.8,
             sweep(1245, 415, 850, lambda p: 2*((p/(2*np.pi))%1)-1) * 0.9,
             noise(850) * 0.55)
blast = noise(160) * env(len(t(160)), a=0.001, r=0.3) * 1.2
save("jump_scare", np.concatenate([blast, scream * env(len(scream), a=0.004, r=0.55)]), 0.95)
save("escape_relief", mix(arp([76, 72, 67, 72, 76, 79], 95, tri), sine(N(52), 570) * 0.25), 0.6)
save("satchel_loss", seq([square(N(m), 110, duty=0.3) * env(len(t(110)), r=0.6) for m in [70, 66, 61, 54]], 20), 0.6)

# --- battle! ---
save("swing_whoosh", sweep(900, 250, 130, lambda p: np.sign(np.sin(p))) * 0.3 + noise(130) * 0.5 * env(len(t(130)), a=0.002, r=0.8), 0.55)
save("wand_zap", mix(sweep(880, 1980, 110), sweep(1320, 2640, 110) * 0.5) * env(len(t(110)), a=0.002, r=0.7), 0.6)
save("ember_blast", mix(sweep(220, 55, 280), noise(280) * 0.6) * env(len(t(280)), a=0.004, r=0.6), 0.7)
save("frost_cast", seq([sine(f, 90) * env(len(t(90)), r=0.85) for f in (2093, 1760, 1397, 1175)], 8), 0.55)
save("hit_impact", mix(sweep(160, 60, 140), noise(90) * 0.8) * env(len(t(140)), a=0.001, r=0.5), 0.75)
save("monster_yelp", sweep(680, 290, 200, lambda p: 2*((p/(2*np.pi))%1)-1) * env(len(t(200)), a=0.004, r=0.6), 0.6)
save("player_hurt", mix(square(140, 220, duty=0.3), noise(220) * 0.4) * env(len(t(220)), a=0.002, r=0.5), 0.7)
sting = seq([mix(square(N(50), 180), square(N(56), 180) * 0.9) * env(len(t(180)), a=0.004, r=0.3),
             mix(square(N(49), 320), square(N(55), 320) * 0.9) * env(len(t(320)), a=0.004, r=0.7)], 25)
save("battle_start", sting, 0.75)
# 1.71s drum loop @ ~140bpm: kick-kick-snare pattern (seamless loop)
kick = lambda: mix(sweep(120, 45, 100) * env(len(t(100)), a=0.001, r=0.6), noise(25) * 0.3)
snare = lambda: mix(noise(110) * 0.8, sine(190, 110) * 0.3) * env(len(t(110)), a=0.001, r=0.8)
beat = 0.214  # seconds per 8th
loop = np.zeros(int(SR * beat * 8))
def put(sig, at):
    i = int(SR * at)
    loop[i:i+len(sig)] += sig[:len(loop)-i]
put(kick(), 0); put(kick(), beat*2); put(snare(), beat*4); put(kick(), beat*5); put(kick(), beat*6); put(snare(), beat*7)
save("battle_drums", loop, 0.5)
save("telegraph_warn", seq([square(740, 120, duty=0.3) * env(len(t(120)), r=0.5), square(990, 200, duty=0.3) * env(len(t(200)), r=0.7)], 30), 0.65)
save("dodge_swish", sweep(1400, 2600, 110) * 0.3 + noise(110) * 0.4 * env(len(t(110)), a=0.001, r=0.9), 0.5)
save("ghost_moan", mix(sine(150, 900) * (0.6 + 0.4 * np.sin(2*np.pi*3.2*t(900))), sine(99, 900) * 0.5) * env(len(t(900)), a=0.08, r=0.5), 0.55)
save("revive_chime", seq([bell(784, 240), bell(1047, 240), bell(1568, 500)], 30), 0.6)
save("mana_fill", seq([tri(N(m), 60) * env(len(t(60)), r=0.7) for m in [76, 79, 83, 88]], 10), 0.5)
save("quest_accept", seq([bell(880, 200), bell(1175, 350)], 40), 0.55)
save("lever_creak", seq([square(f, 70, duty=0.25) * env(len(t(70)), r=0.4) for f in (180, 150, 200, 130, 220)], 15), 0.5)
save("shard_get", mix(arp([84, 91, 96, 103, 108], 110, tri), shiny * 0.6), 0.7)
save("beacon_light", mix(arp([60, 67, 72, 79, 84, 91, 96, 103], 110, tri), arp([72, 79, 84, 91, 96, 103, 108, 115], 110) * 0.4,
     np.concatenate([np.zeros(int(SR*0.3)), windup := sweep(80, 320, 600) * 0.3 * env(len(t(600)), r=0.6)])), 0.75)

# --- JRPG flourishes ---
# classic victory riff: da da da daaa - da daaa
vict = seq([
    mix(square(N(72), 130), square(N(76), 130) * 0.7) * env(len(t(130)), r=0.3),
    mix(square(N(72), 130), square(N(76), 130) * 0.7) * env(len(t(130)), r=0.3),
    mix(square(N(72), 130), square(N(76), 130) * 0.7) * env(len(t(130)), r=0.3),
    mix(square(N(75), 300), square(N(79), 300) * 0.7) * env(len(t(300)), r=0.4),
    mix(square(N(74), 220), square(N(77), 220) * 0.7) * env(len(t(220)), r=0.4),
    mix(square(N(76), 550), square(N(79), 550) * 0.7, square(N(84), 550) * 0.5) * env(len(t(550)), r=0.7),
], 30)
save("jrpg_victory", mix(vict, arp([48, 48, 48, 51, 50, 52], 180) * 0.35), 0.7)
save("crit_hit", mix(sweep(300, 80, 200), noise(120) * 0.9, sine(2200, 60) * 0.5) * env(len(t(200)), a=0.001, r=0.5), 0.8)
save("limit_ready", mix(arp([88, 93, 96], 90, tri), sine(2637, 300) * 0.3 * env(len(t(300)), r=0.9)), 0.6)
starfall = mix(
    seq([sweep(2400 - i * 300, 400 - i * 40, 220) * env(len(t(220)), a=0.002, r=0.8) * (0.8 - i * 0.1) for i in range(5)], 40),
    noise(1300) * 0.25 * env(len(t(1300)), a=0.3, r=0.5))
save("starfall", np.concatenate([starfall, mix(sweep(150, 40, 400), noise(400) * 0.7) * env(len(t(400)), a=0.001, r=0.7)]), 0.85)

# --- field & alchemy ---
save("herb_pick", seq([tri(N(m), 50) * env(len(t(50)), r=0.8) for m in [88, 92]], 8), 0.45)
save("craft_magic", mix(
    arp([79, 84, 88, 93, 96, 100], 90, tri),
    mix(*[sine(f, 700) * env(len(t(700)), r=0.95) * 0.25 for f in (2093, 2637, 3520)])), 0.65)
save("item_use", seq([bell(1175, 180), bell(1568, 320)], 25), 0.55)
save("critter_die", mix(sweep(500, 180, 240, lambda p: 2*((p/(2*np.pi))%1)-1) * env(len(t(240)), a=0.003, r=0.7),
     seq([square(1568, 40) * env(len(t(40)), r=0.9) * 0.4, square(2093, 60) * env(len(t(60)), r=0.9) * 0.4], 15)), 0.6)
save("critter_nip", mix(sweep(400, 200, 110), noise(70) * 0.5) * env(len(t(110)), a=0.002, r=0.6), 0.6)
save("vault_rumble", mix(sweep(70, 45, 900), noise(900) * 0.3 * env(len(t(900)), a=0.15, r=0.4),
     seq([square(N(48), 200, duty=0.3) * env(len(t(200)), r=0.5) * 0.4 for _ in range(3)], 80)), 0.65)

save("portal_warp", mix(
    sweep(300, 2400, 700) * 0.4 * env(len(t(700)), a=0.05, r=0.4),
    sweep(150, 1200, 700, lambda p: 2*((p/(2*np.pi))%1)-1) * 0.3,
    mix(*[sine(f, 900) * env(len(t(900)), r=0.9) * 0.2 for f in (1568, 2093, 2637)])), 0.7)

# --- ambient ---
wind = noise(4000)
from numpy.fft import rfft, irfft
spec = rfft(wind); freqs = np.fft.rfftfreq(len(wind), 1 / SR)
spec *= np.exp(-freqs / 380.0)
wind = irfft(spec); wind /= np.max(np.abs(wind))
lfo = 0.55 + 0.45 * np.sin(2 * np.pi * 0.21 * np.arange(len(wind)) / SR)
save("ambient_wind", wind * lfo, 0.30)

print("wrote", len(os.listdir(OUT)), "wavs")
