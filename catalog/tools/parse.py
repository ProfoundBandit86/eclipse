import re, json, sys
src = open(sys.argv[1], encoding='utf-8').read()
sec = None; out = []
row = re.compile(r'^\| \[(.+?)\]\((https?://[^)]+)\) \| (.*?) \| (.*?) \|\s*$')
for line in src.splitlines():
    if line.startswith('## '): sec = line[3:].strip(); continue
    m = row.match(line)
    if not m or sec in ('Tools & Frameworks', 'Related Lists', 'Contents'): continue
    title, url, desc, author = m.groups()
    am = re.match(r'\[(.+?)\]', author); author = am.group(1) if am else author
    gm = re.match(r'(.+?)\s*\((.+)\)\s*$', title)
    rm = re.match(r'https://github\.com/([^/]+/[^/#?]+)', url)
    out.append(dict(title=title, game=gm.group(1) if gm else title, port=gm.group(2) if gm else '', url=url,
                    repo=rm.group(1).removesuffix('.git') if rm else None, desc=desc, author=author, section=sec))
json.dump(out, open(sys.argv[2], 'w'), ensure_ascii=False, indent=1)
print(len(out), sum(1 for o in out if o['repo']))
