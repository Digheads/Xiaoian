# Licence of the vendored terminal code

Everything under `src/main/java/com/termux/`, `src/main/res/` and
`src/main/jni/termux.c` in this module is copied verbatim from
[termux/termux-app](https://github.com/termux/termux-app), commit
`084d709fbf23ea83b5cb85fd3d795c775be06676`, from its `terminal-emulator` and
`terminal-view` libraries.

That repository states in its root `LICENSE.md`:

> The `termux/termux-app` repository is released under
> [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.
>
> ### Exceptions
>
> - [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
>   code is used which is released under
>   [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check
>   [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator)
>   libraries.

Neither library carries a `LICENSE` file of its own and none of the copied
source files carry a licence header, so the exception above is the only
statement upstream makes about them. It says that these two libraries *contain*
Apache-2.0 code from Terminal Emulator for Android -- it does not relicense the
libraries as a whole. The conservative and, as far as we can tell, correct
reading is therefore:

- **the code in this module is GPLv3-only**, with parts of it derived from
  Apache-2.0 licensed Terminal Emulator for Android.

Note that [plan/03-terminal-emulator.md](../../plan/03-terminal-emulator.md)
claimed these libraries were Apache-2.0. That claim is wrong and is corrected
here.

Practically this changes nothing for the Xiaoian APK, which already links the
`:lorie` module (a fork of Termux:X11, GPLv3) and is itself GPLv3 -- see the
repository root `LICENCE`. It is recorded here so the tree does not carry a
false Apache-2.0 attribution.

Files written for Xiaoian rather than copied from upstream -- `build.gradle`,
`build-natives.sh`, `consumer-rules.pro`, `src/main/jni/ptyspawn.c`,
`src/main/AndroidManifest.xml`, this file and `README.md` -- are under the
repository's own licence.
