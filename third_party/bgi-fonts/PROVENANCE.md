# BGI stroke fonts

Source: https://github.com/gandrewstone/GameMaker/tree/1fe0070a9f8412b5b53923aae7e64769722685a3/tools/BORLANDC/BGI

Upstream revision: `1fe0070a9f8412b5b53923aae7e64769722685a3`.
The upstream repository declares MIT terms; its unmodified LICENSE is retained
here, with the packaged font resources and under Android assets/licenses.
Original Borland notices embedded in each CHR header remain intact. No font is
relabelled as original InfiniFolder work or independently relicensed.
The vector-text-borland project also identifies this distribution as its font
source: https://docs.rs/vector-text-borland/0.1.1/vector_text_borland/ .

These ten bounded binary resources live in `canvas-android/src/main/resources/bgi-fonts`.
They are loaded locally by font ID, never fetched at draw time. EGA and high
resolution modes use their actual stroke outlines and per-character advances.
Font 0 retains the existing CP437 8x8 bitmap font. This imports only font assets,
not upstream code, compiler binaries or executables.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| BOLD.CHR | 14670 | `ee8b45d7491e1bc61936923f8642f1766c58dee7c528fa542e727092a914d07e` |
| EURO.CHR | 8439 | `783392b9fd0414a9c768db7741808811de63c599b34f92acb2d967e74993c6b1` |
| GOTH.CHR | 18063 | `8620ae9db9dd874b842f67f7153721a5681fef3cd9e99af0fde41f3b1194f183` |
| LCOM.CHR | 12083 | `69d210b35c0c3e74cb6cfe9062b471579c43f3cf12fcc8c76f63b96235e9d9bb` |
| LITT.CHR | 5131 | `8a6ecee3378830669335c07c661dc8929c25b01a7163b7fdb32d634d074d6b9c` |
| SANS.CHR | 13596 | `edd4db0e5735ce0872d60137819e2b74a590d069be7585269b7b6d675e319bbc` |
| SCRI.CHR | 10987 | `04fde2931ba5392c55791b0c99c0c6790cfae97e9792b5833e40e87e19730bf7` |
| SIMP.CHR | 8437 | `8d51af7680da29886481166fbc352141e6c615dd97927521b06cb2548f940acf` |
| TRIP.CHR | 16677 | `831963618a5dbf3562e12c3b4b8bab3ed3879143de3c7cd9293be171a44282db` |
| TSCR.CHR | 17355 | `305e2a9657902fef2a52b25e398a5e2fca4cd0ace0fc0208704b2cb5c360b187` |
