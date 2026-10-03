// Joins the TEST server as an unauthenticated Bedrock client and walks a route, so
// HeroPath has real movement to record. Never point this at production: production has
// validate-bedrock-login on, and this would simply be refused there.
const { createClient } = require('bedrock-protocol')
const host = process.env.HOST; if (!host) { console.log('HOST is required: the TEST container IP'); process.exit(4) }
const port = Number(process.env.PORT || 19132)
const legs = JSON.parse(process.env.LEGS || '[[1,0,12],[0,-1,12],[-1,0,8]]') // [dx, dz, seconds]
const speed = 4.0 // blocks per second, a walk

const client = createClient({ host, port, username: process.env.NAME || 'QpcTester', offline: true, followPort: false, version: process.env.BV || '1.26.40' })
let pos = null, tick = 0n, runtimeId = null, corrections = 0, pendingTeleport = false
client.on('start_game', p => {
  pos = { ...p.player_position }
  runtimeId = p.runtime_entity_id
  console.log('start_game at', pos)
})
client.on('move_player', p => {
  if (runtimeId !== null && p.runtime_id == runtimeId) { pos = { ...p.position }; corrections++; pendingTeleport = true }
})
// Respawn: the server says 0 (searching) or 1 (ready); the client answers 2 (client ready).
// Without this a bot killed once stays dead for good, and comes back dead on the next join.
client.on('respawn', p => {
  console.log('respawn state', p.state)
  if (p.state === 1) {
    client.write('respawn', { position: p.position, state: 2, runtime_entity_id: runtimeId })
    pos = { ...p.position }
    pendingTeleport = true
  }
})
client.on('disconnect', p => { console.log('disconnect', p.message); process.exit(2) })
client.on('kick', p => { console.log('kick', p); process.exit(2) })
client.on('spawn', async () => {
  console.log('spawned')
  client.write('set_local_player_as_initialized', { runtime_entity_id: runtimeId })
  await sleep(Number(process.env.WAIT || 3000))
  let moved = 0
  client.on('move_player', () => { moved++ })
  for (const [dx, dz, secs] of legs) {
    const yaw = Math.atan2(-dx, dz) * 180 / Math.PI
    const steps = secs * 20
    for (let i = 0; i < steps; i++) {
      // Confirming a server teleport: stand exactly where the server put us this tick.
      const d = pendingTeleport ? { x: 0, y: 0, z: 0 } : { x: dx * speed / 20, y: 0, z: dz * speed / 20 }
      pos = { x: pos.x + d.x, y: pos.y, z: pos.z + d.z }
      send(yaw, d)
      await sleep(50)
    }
    console.log('leg done at', pos, 'server corrections so far', corrections)
  }
  await sleep(Number(process.env.LINGER || 2000))
  client.disconnect()
  process.exit(0)
})
function send (yaw, d) {
  tick += 1n
  const confirming = pendingTeleport
  client.write('player_auth_input', {
    pitch: 0, yaw, position: pos, move_vector: { x: 0, z: 1 }, head_yaw: yaw,
    input_data: pendingTeleport ? ['up', 'handled_teleport'] : ['up'], input_mode: 'mouse', play_mode: 'normal', interaction_model: 'crosshair',
    interact_rotation: { x: 0, z: 0 }, tick, delta: d, transaction_presence: false,
    item_stack_request_presence: false, block_action_presence: false,
    vehicle_rotation_presence: false, predicted_vehicle_presence: false,
    analogue_move_vector: { x: 0, z: 1 }, camera_orientation: { x: 0, y: 0, z: 1 }, raw_move_vector: { x: 0, z: 1 }
  })
  if (confirming) pendingTeleport = false
}
function sleep (ms) { return new Promise(r => setTimeout(r, ms)) }
setTimeout(() => { console.log('timeout'); process.exit(3) }, 120000)
