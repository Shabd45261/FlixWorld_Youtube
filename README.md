# Flix World — YouTube (CloudStream extension)

One CloudStream extension with both YouTube sources from the Flix World app:

| Provider | What it shows |
|---|---|
| **Flix YouTube** | Videos, channels, playlists. Home = Trending Now, Music, Lofi & Chill, Documentaries, Science & Tech, Cooking |
| **Flix YouTube Shorts** | The Shorts feed (clips ≤ 180s from `#shorts` searches) |

Extraction is delegated to **NewPipeExtractor** (the same library the official
CloudStream YouTube extension uses); CloudStream's own YouTube extractor
resolves playback via `loadExtractor`.

## Install (users)

CloudStream → Settings → Extensions → Add repository, paste:

```
https://raw.githubusercontent.com/Shabd45261/FlixWorld_Youtube/main/repo.json
```

## First-time setup (owner)

The CI (`.github/workflows/build.yml`) builds the plugin and publishes the
compiled `.cs3` + `plugins.json` to a **`builds`** branch, which is what
`repo.json` points at.

1. Create an empty **`builds`** branch (the workflow checks it out):

   ```bash
   git checkout --orphan builds
   rm -rf ./*
   git commit --allow-empty -m "init builds"
   git push -u origin builds
   git checkout main
   ```

2. Repo → Settings → Actions → General → Workflow permissions → **Read and write**.
3. Push any source change (not a `.md`). CI builds and force-pushes `builds`.

## Local build

Needs Android SDK + JDK 17:

```bash
gradle make              # -> FlixWorldYouTube/build/*.cs3
gradle makePluginsJson   # -> build/plugins.json
```

## Note on Shorts

CloudStream has no TikTok-style vertical player; Shorts appear as rows/entries
and play in the normal player. They are pulled from `#shorts <topic>` searches
(the app's `TOPICS`) and filtered to ≤180s.
