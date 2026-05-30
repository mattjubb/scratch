# Version Stage Dashboard

HTML dashboard for managing software project versions across **lane** (snapshot → candidate → release) and **iteration** (next → current → previous) axes. Each combination is a **stage** (9 per project).

## Run locally

```bash
cd ~/projects/version-dashboard
python3 -m http.server 8080
```

Open http://localhost:8080

> Requires a local server so `projects.json` can load (browser file:// restrictions).

## Features

- Project list: `core.datapedia`, `rates.pricer`, `data.analytics`
- Tabs per project: **Description**, **Versions**, **Dependencies**
- Promote versions within a lane or across lanes (with automatic dependency rebase on promote)
- Manual **Rebase** per stage to refresh dependency pins
- Dependency drift highlighting when pins differ from upstream stage versions
- Light / dark mode (persisted)
- Edits saved to `localStorage`; export JSON or reset to seed data
