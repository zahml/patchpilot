import { createServer } from 'node:http'

const port = Number(process.env.PATCHPILOT_STUB_PORT ?? 18081)
const fixtureLine = '+    void rename(String name) { System.out.println(name.trim()); }'
const analysis = {
  summary: 'The new rename method dereferences name without checking for null.',
  findings: [
    {
      severity: 'HIGH',
      ruleId: 'NULL_DEREFERENCE',
      filePath: 'src/Account.java',
      lineStart: 2,
      lineEnd: 2,
      title: 'Potential null dereference',
      explanation: 'name may be null, so calling trim() can throw NullPointerException.',
      suggestion: 'Reject null or validate name before calling trim().',
    },
  ],
}

const server = createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
    response.writeHead(404).end()
    return
  }

  let body = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => {
    body += chunk
  })
  request.on('end', () => {
    let payload
    try {
      payload = JSON.parse(body)
    } catch {
      response.writeHead(400).end()
      return
    }

    const userMessage = payload.messages?.find((message) => message.role === 'user')?.content ?? ''
    if (!userMessage.includes(fixtureLine)) {
      response.writeHead(422, { 'content-type': 'application/json' })
        .end(JSON.stringify({ error: 'This local fixture only accepts the documented unified diff.' }))
      return
    }

    response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({
      id: 'patchpilot-local-e2e',
      choices: [{ message: { role: 'assistant', content: JSON.stringify(analysis) } }],
    }))
  })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`PatchPilot local model fixture listening on http://127.0.0.1:${port}`)
})
