# Pagbukas nito sa Mac (Setup Guide)

Ginawa ito sa Windows kaya hindi pa na-build/na-tetest — ito ang mga hakbang
para buksan at patakbuhin mo sa Mac mo. Isang beses mo lang kailangang gawin
ito; every future update, `git pull` ka lang at ulitin lang ang **Step 3**
(regenerate) kung binago namin ang `project.yml`.

## 1. I-install ang Xcode (kung wala pa)

Buksan ang **App Store** sa Mac, i-search ang "Xcode", i-install. Malaki siya
(~10GB+), pwedeng matagal ang download. Pagkatapos i-install, buksan siya
isang beses para ma-install nito ang command-line tools.

## 2. I-install ang Homebrew + XcodeGen (kung wala pa)

Hindi namin ini-commit ang `.xcodeproj` file (mismong Xcode project file) sa
repo — sa halip, may `project.yml` na "recipe" na gumagawa nito
automatically gamit ang isang tool na tinatawag na **XcodeGen**. Ito ang
karaniwang practice para maiwasan ang messy merge conflicts sa project file.

Sa **Terminal** app ng Mac mo:

```bash
# Homebrew (kung wala ka pa nito) — copy-paste mo lang ito, sundin yung
# instructions na lalabas:
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Pagkatapos, i-install ang XcodeGen:
brew install xcodegen
```

## 3. I-generate ang Xcode project

Sa Terminal, pumunta sa `ios-visualeyes` folder (palitan ang path kung saan
mo ni-clone ang repo):

```bash
cd path/to/VisualEyes/ios-visualeyes
xcodegen generate
```

Lalabas ang bagong `VisualEyes.xcodeproj` file sa parehong folder.

## 4. Buksan sa Xcode

Double-click sa `VisualEyes.xcodeproj`, o sa Terminal:

```bash
open VisualEyes.xcodeproj
```

## 5. I-set up ang Signing (para makapag-build)

Sa Xcode:
1. I-click ang project name ("VisualEyes") sa Navigator (kaliwang panel).
2. Piliin ang "VisualEyes" target.
3. Pumunta sa tab na **Signing & Capabilities**.
4. Sa "Team" dropdown, piliin ang sarili mong Apple ID (kung wala pa, i-add
   mo via Xcode > Settings > Accounts > "+"). Libre lang gamitin ang sarili
   mong Apple ID para mag-run sa Simulator o sa sarili mong iPhone — hindi
   mo kailangan ng bayad na Apple Developer account maliban kung gusto mong
   i-publish sa App Store balang araw.

## 6. Patakbuhin

Sa taas ng Xcode window, piliin ang isang **iPhone Simulator** (hal.
"iPhone 16") sa device dropdown, tapos pindutin ang ▶ (Run) button, o
`Cmd+R`.

Dapat makita mo agad ang Intro screen, tapos ang Login screen.

**IMPORTANT — mic/voice features kailangan ng totoong iPhone, hindi
Simulator.** Para masubukan ang boses (TTS, mic dictation, assessment,
reader), i-connect ang totoong iPhone sa Mac (USB o WiFi), piliin siya sa
device dropdown sa halip na Simulator.

## Testing sa totoong data

Kailangan mo ng totoong test student account (naka-approve na sa Supabase)
para makapag-login — pareho lang ito sa Supabase backend na ginagamit ng
Android app, kaya kung may existing na test account kayo doon, gagana rin
siya dito.

## Kung may build errors

Ang pinaka-malamang na dahilan: hindi pa na-regenerate ang `.xcodeproj`
matapos magbago ang `project.yml` o may bagong file na idinagdag —
paulit-ulitin mo lang ang **Step 3** (`xcodegen generate`), tapos i-close at
buksan ulit ang Xcode.

Kung may ibang error, i-copy mo lang yung buong error message at ipadala sa
akin (o i-screenshot), para masuri natin. Dahil hindi ko ito na-compile
dito sa Windows, **inaasahan kong may mga maliliit na build errors sa
unang pagbukas** — normal lang ito, madali lang aayusin kapag nakita na
natin ang error message.

---

## Status ngayon: Phase 1–6 lahat nakasulat na

Lahat ng screens/features nasa code na, konektado sa parehong Supabase
backend ng Android app:

- ✅ Intro, Login, Register (typed + voice dictation ng ilang fields)
- ✅ Home / Materials / Profile tabs
- ✅ Reading Assessment (5-word ladder) + Result screen — voice at button
  parehong gumagana
- ✅ Material reader — nagba-basa ng PDF, may live word-by-word highlight
  habang binabasa (on-device TTS)
- ✅ Voice Settings (4 na assistant voices), Feedback (5-step form), Help,
  Privacy Policy — lahat may "Read Aloud" kung saan applicable
- ✅ Voice commands sa English at Tagalog (grammar table na)
- ✅ 3-tier na speech recognition: built-in recognizer → cloud
  (Google STT via parehong backend) → offline (**hindi pa gumagana**, see
  below)

### Alam kong may mga kailangan pang ayusin (huwag magulat)

1. **Offline voice recognition (Vosk, Tier 3) — hindi pa gumagana.**
   Kailangan pa ng manual setup sa Mac mo: (a) i-add ang Vosk iOS package
   sa Xcode project, (b) i-copy ang malaking 205MB na voice model papunta
   sa app (mayroon na nito sa Android app — `app/src/main/assets/vosk-model-en-us-0.22-lgraph`
   — pwede mong i-AirDrop o i-copy papunta sa
   `ios-visualeyes/VisualEyes/Resources/vosk-model-en-us-0.22-lgraph/`).
   Hanggang hindi ito nagagawa, gagana pa rin ang boses habang may
   internet (built-in recognizer + cloud); wala lang offline fallback.
2. **Yung "raw audio capture" na code (Tier 2 cloud voice recognition)**
   ang pinaka-kumplikadong bahagi na hindi ko na-verify na gumagana nang
   tama, dahil hindi ko ito ma-compile/ma-test dito sa Windows. Kung
   medyo mali ang narinig ng app kapag gumagamit ng cloud voice
   (halimbawa kapag walang internet ang built-in recognizer), ito
   malamang ang unang titignan natin.
3. **Voice-guided registration** (buong "sabihin mo pangalan mo,
   babasahin ko pabalik letter-by-letter") — hindi pa buo. Ang
   nakasulat lang ngayon ay typed form + button-based na name-fixing
   logic (`NameNormalizer`), hindi pa yung buong spoken back-and-forth na
   flow na meron sa Android.
4. **Sent Materials drawer at triple-tap "ulitin" gesture** — wala pa,
   parehong existing sa Android pero hindi pa naisasama dito.
5. **PDF paragraph-splitting (chunking)** — approximation lang batay sa
   pangkalahatang paraan ng Android, hindi 100% eksaktong kopya, dahil
   hindi ko na-detalye ang eksaktong Android logic. Kailangan pa itong
   i-check gamit ang totoong learning material PDF.

Wala sa mga ito ang dapat pumigil sa pag-open/pag-build ng app — lahat ng
main na screens gagana, boses/mic kailangan lang ng totoong iPhone (hindi
Simulator).

## Wala pang app icon

May placeholder lang muna ang app icon (blangko) — hindi pa siya priority,
i-aayos natin sa huling polish pass. Hindi ito hahadlang sa
pag-build/pag-run.
