# MobileGlues 2.0.0

Copyright (c) 2025–2026 MobileGL-Dev. Licensed under LGPL-2.1-only;
see LICENSE. This launcher dynamically loads unmodified libmobileglues.so
from the official, signature- and checksum-verified 2.0.0 release APK.
The plugin application's UI, services and JNI information helper are not included.

Release: https://github.com/MobileGL-Dev/MobileGlues-release/releases/tag/V2.0.0
Source and native build scripts:
https://github.com/MobileGL-Dev/MobileGlues/tree/f56af31d3e490d95808b3adc4ace20234249a37a
Release plugin/build integration:
https://github.com/MobileGL-Dev/MobileGlues-plugin/tree/c0e48848ef3850e326606ba092774edbb7d904ff
The latter commit pins the renderer source above as its MobileGlues submodule.
provenance.json records checksums, source commits and recursive submodule commits.

To obtain the renderer source and its dependencies for modification/rebuilding:

    git clone https://github.com/MobileGL-Dev/MobileGlues.git
    cd MobileGlues
    git checkout f56af31d3e490d95808b3adc4ace20234249a37a
    git submodule update --init --recursive

The library can be replaced under FCL/src/main/jniLibs/<abi>/ and the
open-source launcher rebuilt and signed. The checksum audit pins the distributed
release for reproducibility; when intentionally replacing it, update provenance.json
and the audit to reflect your build. No reverse-engineering restriction is added.

Third-party components included by the renderer (their license texts accompany
this notice):

- SPIRV-Cross, KhronosGroup: Apache-2.0 and bundled notices (SPIRV-Cross-LICENSE).
- glslang, KhronosGroup and contributors: component-specific permissive licenses
  (glslang-LICENSE.txt).
- cJSON, Dave Gamble and contributors: MIT (cJSON-LICENSE.txt).
- FidelityFX-FSR, Advanced Micro Devices: MIT (FidelityFX-FSR-LICENSE.txt).
- Perfetto, The Android Open Source Project and contributors: Apache-2.0 and
  bundled notices (Perfetto-LICENSE).
- xxhash C++ implementation, Stephan Brumme: MIT (xxHash-LICENSE), source
  https://github.com/stbrumme/xxhash/tree/c2866db364b6ea3a11933e62235ddc166ba18565.
- flat_hash_map, Malte Skarupke and contributors, MobileGL-Dev fork:
  Boost Software License 1.0 (flat_hash_map-LICENSE).

All upstream copyright notices remain applicable. This notice does not change
the license of the renderer, its dependencies, or the launcher.
