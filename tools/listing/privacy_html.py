"""PRIVACY.md → docs/marketplace/privacy-policy.html, the HTML JetBrains Marketplace's privacy-policy field takes.

    python3 tools/listing/privacy_html.py PRIVACY.md docs/marketplace/privacy-policy.html
"""
import html
import re
import sys


def inline(s):
    s = html.escape(s, quote=False)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"(?<![\w*])_(.+?)_(?!\w)", r"<i>\1</i>", s)
    s = re.sub(r"`(.+?)`", r"<code>\1</code>", s)
    # Links; in-page anchors become plain text (Marketplace shows the policy without them).
    s = re.sub(r"\[(.+?)\]\(#.+?\)", r"\1", s)
    return re.sub(r"\[(.+?)\]\((.+?)\)", r'<a href="\2">\1</a>', s)


def convert(md):
    out, para, items, rows = [], [], [], []

    def flush():
        nonlocal para, items, rows
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
        if items:
            out.append("<ul>\n" + "\n".join(f"  <li>{inline(i)}</li>" for i in items) + "\n</ul>")
        if rows:
            head, body = rows[0], [r for r in rows[2:]]
            out.append("<table>\n  <tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr>\n" +
                       "\n".join("  <tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body) + "\n</table>")
        para, items, rows = [], [], []

    for line in md.splitlines():
        if line.startswith("# "):
            flush(); out.append(f"<h2>{inline(line[2:])}</h2>")
        elif line.startswith("## "):
            flush(); out.append(f"<h3>{inline(line[3:])}</h3>")
        elif line.startswith("- "):
            if para or rows: flush()
            items.append(line[2:])
        elif line.startswith("  ") and items:
            items[-1] += " " + line.strip()
        elif line.startswith("|"):
            if para or items: flush()
            rows.append([c.strip() for c in line.strip().strip("|").split("|")])
        elif not line.strip():
            flush()
        else:
            if items or rows: flush()
            para.append(line.strip())
    flush()
    return "\n".join(out) + "\n"


src, dst = sys.argv[1], sys.argv[2]
open(dst, "w").write(convert(open(src).read()))
print("wrote", dst)
