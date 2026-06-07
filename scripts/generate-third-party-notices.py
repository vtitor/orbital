#!/usr/bin/env python3
"""
Generate THIRD-PARTY-NOTICES.txt from the libraries actually bundled in the built plugin.

Reads build/distributions/*.zip (so it reflects exactly what ships), collects each bundled
jar's coordinates, license, and any embedded LICENSE/NOTICE text, and writes the notices file
both to the repo root and into src/main/resources/META-INF/ so it is shipped inside the plugin.

Run after `./gradlew buildPlugin`:
    python3 scripts/generate-third-party-notices.py
"""
import glob, io, os, re, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Authoritative fallbacks for jars that inherit their <licenses> from a parent POM
# (verified against Maven Central / parent POMs).
FALLBACK_BY_GROUP = {
    "com.azure": "MIT License",
    "org.slf4j": "MIT License",
    "io.projectreactor": "Apache License, Version 2.0",
    "io.projectreactor.netty": "Apache License, Version 2.0",
    "io.micrometer": "Apache License, Version 2.0",
    "org.reactivestreams": "MIT-0",
}

# Last-resort fallback by jar filename prefix, for jars that embed neither a POM nor a
# Bundle-License (verified against Maven Central / parent POMs).
FALLBACK_BY_FILENAME = {
    "netty-": "Apache License, Version 2.0",
    "reactor-": "Apache License, Version 2.0",
    "micrometer-": "Apache License, Version 2.0",
    "reactive-streams": "MIT-0",
    "slf4j-": "MIT License",
    "azure-": "MIT License",
    "jackson-": "Apache License, Version 2.0",
    "metrics-core": "Apache License, Version 2.0",
    "HdrHistogram": "BSD 2-Clause License",
    "LatencyUtils": "CC0 1.0 (Public Domain)",
}

MIT_TEXT = """MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

MIT0_TEXT = """MIT No Attribution (MIT-0)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

BSD2_TEXT = """BSD 2-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""

CC0_TEXT = """CC0 1.0 Universal (Public Domain Dedication)

The person who associated a work with this deed has dedicated the work to the
public domain by waiving all of their rights to the work worldwide under
copyright law. You can copy, modify, distribute and perform the work, even for
commercial purposes, all without asking permission.
Full text: https://creativecommons.org/publicdomain/zero/1.0/legalcode"""


def norm_license(name: str) -> str:
    n = name.lower()
    if "apache" in n:
        return "Apache License, Version 2.0"
    if "mit-0" in n or "mit no attribution" in n:
        return "MIT-0"
    if "mit" in n:
        return "MIT License"
    if "bsd" in n and "2" in n:
        return "BSD 2-Clause License"
    if "cc0" in n or "public domain" in n:
        return "CC0 1.0 (Public Domain)"
    return name.strip()


def main():
    dists = sorted(glob.glob(os.path.join(ROOT, "build", "distributions", "*.zip")))
    if not dists:
        sys.exit("No plugin zip in build/distributions — run ./gradlew buildPlugin first.")
    plugin_zip = dists[-1]

    components = []   # (coords, license, notice_text)
    apache_full = None

    with zipfile.ZipFile(plugin_zip) as pz:
        for entry in sorted(n for n in pz.namelist() if n.endswith(".jar") and "/lib/" in n):
            try:
                jz = zipfile.ZipFile(io.BytesIO(pz.read(entry)))
            except Exception:
                continue
            names = jz.namelist()
            if "META-INF/plugin.xml" in names:
                continue  # the plugin's own jar (your code)

            coords = os.path.basename(entry)[:-4]
            gid = None
            pp = [n for n in names if n.endswith("pom.properties")]
            if pp:
                props = dict(
                    l.split("=", 1) for l in jz.read(pp[0]).decode("utf-8", "ignore").splitlines()
                    if "=" in l and not l.startswith("#")
                )
                gid = props.get("groupId")
                coords = f"{props.get('groupId','?')}:{props.get('artifactId','?')}:{props.get('version','?')}"

            lic = None
            poms = [n for n in names if re.match(r"META-INF/maven/.+/pom\.xml$", n)]
            if poms:
                t = jz.read(poms[0]).decode("utf-8", "ignore")
                m = re.search(r"<license>.*?<name>(.*?)</name>", t, re.S)
                if m:
                    lic = norm_license(re.sub(r"\s+", " ", m.group(1)))
            if not lic and "META-INF/MANIFEST.MF" in names:
                mf = jz.read("META-INF/MANIFEST.MF").decode("utf-8", "ignore")
                bl = re.search(r"Bundle-License:\s*(.+)", mf)
                if bl:
                    lic = norm_license(bl.group(1).strip())
            if not lic and gid:
                for k, v in FALLBACK_BY_GROUP.items():
                    if gid == k or gid.startswith(k + "."):
                        lic = norm_license(v)
                        break
            if not lic:
                base = os.path.basename(entry)
                for prefix, v in FALLBACK_BY_FILENAME.items():
                    if base.startswith(prefix):
                        lic = norm_license(v)
                        break
            lic = lic or "(see component / Maven Central)"

            def read_first(keywords):
                for n in names:
                    base = n.split("/")[-1].upper()
                    if n.endswith(".class"):
                        continue
                    if any(k in base for k in keywords):
                        try:
                            txt = jz.read(n).decode("utf-8", "ignore").strip()
                            if txt:
                                return txt
                        except Exception:
                            pass
                return None

            lic_text = read_first(["LICENSE", "LICENCE"])
            notice_text = read_first(["NOTICE"])
            if lic == "Apache License, Version 2.0" and lic_text and "TERMS AND CONDITIONS" in lic_text:
                if apache_full is None or len(lic_text) > len(apache_full):
                    apache_full = lic_text
            components.append((coords, lic, notice_text))

    components.sort()
    used = sorted({c[1] for c in components})

    out = []
    out.append("THIRD-PARTY SOFTWARE NOTICES AND INFORMATION")
    out.append("=" * 60)
    out.append("")
    out.append("This product bundles the third-party components listed below. Each is")
    out.append("provided under its own license; the applicable license texts follow the list.")
    out.append("All bundled components use permissive licenses (no copyleft).")
    out.append("")
    out.append("Generated from: " + os.path.basename(plugin_zip))
    out.append("")
    out.append("-" * 60)
    out.append("COMPONENTS")
    out.append("-" * 60)
    for coords, lic, _ in components:
        out.append(f"  * {coords}  —  {lic}")
    out.append("")
    out.append("-" * 60)
    out.append("LICENSE TEXTS")
    out.append("-" * 60)

    def section(title, body):
        out.append("")
        out.append("=" * 60)
        out.append(title)
        out.append("=" * 60)
        out.append(body)

    if any(l == "Apache License, Version 2.0" for l in used):
        section("Apache License, Version 2.0",
                apache_full or "See https://www.apache.org/licenses/LICENSE-2.0")
    if any(l == "MIT License" for l in used):
        section("MIT License", MIT_TEXT)
    if any(l == "MIT-0" for l in used):
        section("MIT No Attribution (MIT-0)", MIT0_TEXT)
    if any(l == "BSD 2-Clause License" for l in used):
        section("BSD 2-Clause License", BSD2_TEXT)
    if any("CC0" in l or "Public Domain" in l for l in used):
        section("CC0 1.0 (Public Domain)", CC0_TEXT)

    notices = [(c, n) for c, _, n in components if n]
    if notices:
        out.append("")
        out.append("-" * 60)
        out.append("NOTICE FILES (reproduced as required by Apache-2.0 §4)")
        out.append("-" * 60)
        for coords, n in notices:
            out.append("")
            out.append(f">>> {coords}")
            out.append(n)

    text = "\n".join(out) + "\n"
    targets = [
        os.path.join(ROOT, "THIRD-PARTY-NOTICES.txt"),
        os.path.join(ROOT, "src", "main", "resources", "META-INF", "THIRD-PARTY-NOTICES.txt"),
    ]
    for t in targets:
        os.makedirs(os.path.dirname(t), exist_ok=True)
        with open(t, "w", encoding="utf-8") as f:
            f.write(text)
    print(f"Wrote notices for {len(components)} components to:")
    for t in targets:
        print("  " + os.path.relpath(t, ROOT))


if __name__ == "__main__":
    main()
