# bedrock-walker: a scripted player for end-to-end tests

HeroPath records players, so testing it needs a player. Java bots (mineflayer) did not
speak Minecraft 26.2 yet, but a Geyser server accepts a Bedrock client, and
[`bedrock-protocol`](https://github.com/PrismarineJS/bedrock-protocol) is one.

**Only against a test server** that has `validate-bedrock-login: false` in Geyser's config.
A real server with the Xbox check on refuses it ("Please log into Xbox"). Put the check
back on when you are done.

```bash
npm i bedrock-protocol@3.59.0
# HOST is required: the test server's own address. followPort is off, so the client can
# not wander to another server that advertises a different port.
HOST=172.20.0.4 LEGS='[[1,0,12],[0,-1,12]]' node walk.js     # [dx, dz, seconds] per leg, 4 blocks/s
```

What it had to learn to move, each found by watching the server correct it:

- send `set_local_player_as_initialized` after spawning;
- confirm every server teleport with `handled_teleport` in `player_auth_input`, standing
  exactly where the server put it; until then Geyser ignores movement;
- answer a `respawn` with state 2, or a bot killed once comes back dead next time.
