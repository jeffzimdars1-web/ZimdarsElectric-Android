
## Price book (updatable without an app release)
- Lives in Supabase project `zimdars-electric` (ref `dmaslbfwpsfnnnyushbc`), table `public.app_content`, row `key = 'pricebook'`, column `data` (jsonb).
- To change prices: update that row's `data` (keep the shape below) and set `"updated"` to today's date. The app downloads it each time the Price book screen opens (More → Price book, or "Price book →" on a quote) and keeps the last copy on the phone.
- Shape: `{title, updated, intro, rates:[{value,label}], steps:[...], sections:[{title, columns:[...], items:[{name, note, prices:[...]}]}], callouts:[{kind:"warn"|"info", title, bullets:[...]}], examples:[{title, lines:[[label, amount]], total:[label, amount], note}], footer}`. Unknown keys are ignored; missing parts are skipped.
- Served by edge function `content` (needs the app's `x-app-key`). Prices are kept out of this public repo on purpose.
