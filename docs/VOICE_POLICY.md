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

## Cloud voice policy
The previous Google Cloud ar-XA backend is a cloud service, not an offline/free voice pack. It may be offered only as a separate optional mode after deployment, billing, quotas, cost disclosures, and privacy controls are configured. Never imply that unlimited cloud synthesis is free.
