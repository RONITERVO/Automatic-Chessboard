# Third-party notices

- **Stockfish 18** — GNU GPL v3 — <https://github.com/official-stockfish/Stockfish/tree/sf_18>
- **chesslib 1.3.7** — Apache License 2.0 — <https://github.com/bhlangonijr/chesslib/tree/1.3.7>
- **Apache Commons Lang 3.18.0** (transitive chesslib dependency) — Apache License 2.0 — <https://commons.apache.org/proper/commons-lang/>

The complete Apache License 2.0 text is distributed inside every APK at
`assets/third_party/APACHE-2.0.txt`. Apache Commons Lang's applicable NOTICE is
at `assets/third_party/COMMONS-LANG-NOTICE.txt`. The chesslib 1.3.7 source tag
contains no separate NOTICE file; its complete Apache license is the shared
license file above.

The Android app itself is GNU GPL version 3 or later. The Stockfish download
script uses the official `sf_18` release and pinned checksums. Stockfish's
release tar contains the corresponding source and `Copying.txt`; anyone
distributing an APK containing the binary must also satisfy GPLv3 corresponding
source and license delivery obligations. A release process should publish the
unaltered official tar beside the APK or otherwise provide the complete source
in a GPL-compliant form.
