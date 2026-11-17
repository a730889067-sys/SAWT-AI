# SAWT AI voice policy

## Required catalogue
SAWT AI's primary Arabic catalogue is Modern Standard Arabic only (العربية الفصحى), not regional dialect voices. It must provide exactly these first two voice profiles:
- **Male Fusha Narrator:** deep, mature, warm, steady, and clearly articulated.
- **Female Fusha Narrator:** clear, smooth, confident, and clearly articulated.

These are product requirements. They are not satisfied by simply lowering the pitch of a female voice or by exposing whichever Arabic voice happens to be installed on a phone.

## Local voice requirements
- Prefer on-device model packs so users can generate speech without an account, API key, network connection, or per-character provider fee after downloading the model.
- The application must download model packs only from the declared publisher/source, verify checksums, display model size, preserve required license notices, and handle interrupted downloads.
- Do not mark a model as Android-compatible until it has been integrated and tested on Android devices.
- Do not advertise a model as fully accurate Fusha until native Arabic listening QA covers emphatic letters, pharyngeal consonants, hamza, vowels, diacritics, numerals, and long passages.
- Do not fall back silently to an installed system voice; clearly label any optional device voice as device-provided and unverified for the SAWT catalogue.

## Current candidate assessment (research only; not yet integrated)
- **Nabra-7M-Distill:** its model card describes one Modern Standard Arabic voice, a single voice pack, a 28.7 MB model, Apache-2.0 licensing, and phone-class CPU measurements. The parent Nabra model identifies this as a female voice. It is a promising female candidate, but its published inference path uses a Python/Kokoro-based implementation; an Android-compatible runtime and on-device pronunciation tests are still required. Sources: https://huggingface.co/oddadmix/Nabra-7M-Distill and https://huggingface.co/oddadmix/Nabra-82M-v0.1
- **3arab-TTS v2 VoiceDesign:** the model card describes voice design from text descriptions and an Apache-2.0 license, but reports about 553M parameters. This is too large to assume it will run well on ordinary Android phones; benchmark before considering it. Source: https://huggingface.co/sherif1313/3arab-TTS-500M-v2-VoiceDesign
- **Fasih-TTS-V1:** its card describes a professional male MSA voice, but uses the Coqui Public Model License for non-commercial use. Do not include it in a public/commercial app unless the rights holder grants a separate suitable license. Source: https://huggingface.co/NightPrince/Fasih-TTS-V1
- **Piper ar_JO-kareem:** this is labeled Arabic (Jordan), not verified MSA, and its model card says the training-data license is "See URL"; do not present it as the approved Fusha male voice without further rights and pronunciation verification. Source: https://huggingface.co/rhasspy/piper-voices/tree/main/ar/ar_JO/kareem/medium

- **Nabra-7M-Distill ONNX:** an Apache-2.0 ONNX export exists (16 MB FP16) and its JavaScript package runs in a browser using ONNX Runtime Web and bundled eSpeak-NG WASM. This makes a WebView proof-of-concept plausible, but it is still only one female MSA voice, not a native Android engine already verified in SAWT AI. The package also bundles eSpeak-NG under GPL-3.0-or-later, which requires preserving its license/notice and reviewing distribution obligations. Sources: https://huggingface.co/oddadmix/Nabra-7M-Distill-ONNX and https://github.com/Oddadmix/nabra-tts
- **Habibi-TTS Specialized/MSA:** the MSA checkpoint is Apache-2.0 and an ONNX export exists, but the source checkpoint is about 1.35 GB and its ONNX path is in-context voice cloning that requires a reference recording and transcript. It is not a pair of ready-made male/female presets, and its Android latency/memory use is unverified. Sources: https://huggingface.co/SWivid/Habibi-TTS/tree/main/Specialized/MSA and https://huggingface.co/OpenVoiceOS/phoonnx-f5tts
- **A3S:** the published model lists three Arabic speaker identities and Apache-2.0, but its model is reported at about 0.9B parameters and its example inference loads it on CUDA. It is not a sensible default for ordinary Android phones without an optimized mobile export and device benchmarks. Source: https://huggingface.co/MahmoudIbrahim/A3S
- **Arabic FastPitch multi-speaker ONNX community weights:** a repository reports one additional male and two female speakers, but describes the multispeaker model as in progress and the weight/license chain is not clear enough for SAWT AI distribution. Do not package it without rights verification and human listening QA. Source: https://github.com/asgmodel/modeltts-ar

## Cloud voice policy
The previous Google Cloud ar-XA backend is a cloud service, not an offline/free voice pack. It may be offered only as a separate optional mode after deployment, billing, quotas, cost disclosures, and privacy controls are configured. Never imply that unlimited cloud synthesis is free.
