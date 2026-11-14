![Three women on a shoreline: smart glasses spot the litter, a grabber picks it up, a bag collects it](assets/cover.jpg)

# Plokka

Built at [Junction 2026](https://2026.hackjunction.com), 13–15 November 2026, Espoo.

Strava for [plogging](https://en.wikipedia.org/wiki/Plogging), that is, picking up litter while you walk or run. A sensor on a litter grabber counts every item you pick up, your phone records the route, and every outing turns into stats you can share and compete on.

| Folder | What it does |
|---|---|
| [contract/](contract/) | The event format that all parts share |
| [api/](api/) | Receives events, puts picks on the route, computes stats |
| [web/](web/) | What people see: outings, stats, leaderboard |
| [grabber/](grabber/) | Recognises a pick on the Arduino UNO Q |
| [glasses/](glasses/) | Spots litter and recognises its type with Meta glasses |

Each part talks only to the API, never to another part.

Challenges: Arduino *The Green European Renaissance*, Meta *AI Glasses for Everyone, Everyday*, Atech *Real World Challenge*.
