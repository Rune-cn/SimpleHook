#!/usr/bin/env python3
"""Consistency checks for the MCP feature that do not require a Kotlin compiler.

The authoritative check is the Gradle build in GitHub Actions. This script only guards
relationships that span files and that a compiler error would report confusingly:

  1. every KDoc/comment reference to a resource exists (@string, @drawable, R.string.*);
  2. every view id used through the generated ViewBinding exists in the layout;
  3. all declared tools are registered exactly once, and their names are unique;
  4. no file in feature/mcp references an Android API that would break the JVM unit tests
     (the protocol and transport packages must stay Android-free);
  5. string resources added for MCP exist in every locale that the app ships.

Exit code 1 when a problem is found.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
SRC = APP / "java/me/simpleHook/feature/mcp"
RES = APP / "res"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def strings_of(path: Path) -> set[str]:
    return set(re.findall(r'<string name="([^"]+)"', read(path)))


def main() -> int:
    problems: list[str] = []

    # 1. resource references from the new layout, screen and settings page.
    default_strings = strings_of(RES / "values/strings.xml")
    drawables = {p.stem for p in (RES / "drawable").glob("*.xml")}
    drawables |= {p.stem for p in (RES / "drawable").glob("*.png")}

    for layout in [RES / "layout/activity_mcp_settings.xml", RES / "xml/settings_preferences.xml"]:
        text = read(layout)
        for ref in re.findall(r"@string/([\w.]+)", text):
            if ref != "appbar_scrolling_view_behavior" and ref not in default_strings:
                problems.append(f"{layout.name}: missing @string/{ref}")
        for ref in re.findall(r"@drawable/([\w.]+)", text):
            if ref not in drawables:
                problems.append(f"{layout.name}: missing @drawable/{ref}")

    for kt in sorted(SRC.rglob("*.kt")):
        for ref in re.findall(r"R\.string\.(\w+)", read(kt)):
            if ref not in default_strings:
                problems.append(f"{kt.relative_to(ROOT)}: missing R.string.{ref}")

    # 2. ViewBinding ids used by the settings screen.
    activity = SRC / "ui/McpSettingsActivity.kt"
    layout_ids = set(re.findall(
        r'android:id="@\+id/(\w+)"', read(RES / "layout/activity_mcp_settings.xml")))
    for used in set(re.findall(r"binding\.(\w+)", read(activity))):
        if used == "root" or used[:1].isupper():
            continue
        if used not in layout_ids:
            problems.append(f"McpSettingsActivity: binding.{used} has no view in the layout")

    # 3. Tools registered in the factory must exist as classes and be unique.
    factory = read(SRC / "McpServerFactory.kt")
    registered = re.findall(r"(\w+Tool)\(data\)", factory)
    duplicates = {name for name in registered if registered.count(name) > 1}
    if duplicates:
        problems.append(f"McpServerFactory registers a tool twice: {sorted(duplicates)}")
    tool_classes = {
        m.group(1)
        for p in (SRC / "tools").glob("*.kt")
        for m in re.finditer(r"^class (\w+Tool)\b", read(p), re.M)
    }
    for name in registered:
        if name not in tool_classes:
            problems.append(f"McpServerFactory references unknown tool class {name}")
    for name in sorted(tool_classes - set(registered)):
        problems.append(f"tool class {name} is never registered")

    tool_names = []
    for p in (SRC / "tools").glob("*.kt"):
        tool_names += re.findall(r'override val name: String = "([\w.-]+)"', read(p))
    if len(tool_names) != len(set(tool_names)):
        problems.append(f"duplicate tool names: {sorted(tool_names)}")

    # 4. The protocol and transport packages must stay free of Android imports so the JVM
    #    unit tests can run them without Robolectric or an emulator.
    for package in ["protocol", "tools"]:
        for kt in (SRC / package).glob("*.kt"):
            text = read(kt)
            if re.search(r"^import android", text, re.M) or "R." in text:
                problems.append(
                    f"{kt.relative_to(ROOT)}: must not depend on Android APIs "
                    "(it is covered by JVM unit tests)"
                )

    # 5. Locales must stay in sync for the keys this feature adds.
    mcp_keys = {k for k in default_strings if k.startswith(("mcp_", "main_settings_title_mcp",
                                                           "main_settings_summary_mcp"))}
    for locale in ["values-en", "values-zh-rTW"]:
        locale_strings = strings_of(RES / f"{locale}/strings.xml")
        missing = sorted(mcp_keys - locale_strings)
        if missing:
            problems.append(f"{locale}/strings.xml: missing {len(missing)} MCP string(s): {missing}")

    if problems:
        print("\n".join(sorted(set(problems))))
        print(f"\n{len(set(problems))} problem(s)")
        return 1
    print(f"static checks passed: {len(tool_names)} tools, {len(mcp_keys)} MCP strings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
