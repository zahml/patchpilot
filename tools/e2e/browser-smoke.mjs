import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const uiUrl = process.env.PATCHPILOT_UI_URL ?? 'http://127.0.0.1:5173'
const chromePath = process.env.CHROME_BIN
  ?? (process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    : 'google-chrome')
const title = `E2E nullable account ${new Date().toISOString()}`
const diffText = [
  'diff --git a/src/Account.java b/src/Account.java',
  'index 1234567..89abcde 100644',
  '--- a/src/Account.java',
  '+++ b/src/Account.java',
  '@@ -1,2 +1,3 @@',
  ' class Account {',
  '+    void rename(String name) { System.out.println(name.trim()); }',
  ' }',
].join('\n')

if (process.env.CHROME_BIN && !existsSync(chromePath)) {
  throw new Error(`CHROME_BIN does not point to an executable: ${chromePath}`)
}

const profile = mkdtempSync(join(tmpdir(), 'patchpilot-e2e-chrome-'))
const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  '--remote-debugging-port=0',
  '--remote-debugging-address=127.0.0.1',
  `--user-data-dir=${profile}`,
  uiUrl,
], { stdio: 'ignore' })

class DevToolsClient {
  #socket
  #nextId = 0
  #pending = new Map()

  constructor(url) {
    this.#socket = new WebSocket(url)
    this.ready = new Promise((resolve, reject) => {
      this.#socket.addEventListener('open', resolve, { once: true })
      this.#socket.addEventListener('error', reject, { once: true })
    })
    this.#socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      if (!message.id) return
      const pending = this.#pending.get(message.id)
      if (!pending) return
      this.#pending.delete(message.id)
      if (message.error) pending.reject(new Error(message.error.message))
      else pending.resolve(message.result)
    })
  }

  async send(method, params = {}) {
    await this.ready
    const id = ++this.#nextId
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.#pending.delete(id)
        reject(new Error(`Chrome DevTools command timed out: ${method}`))
      }, 65000)
      this.#pending.set(id, {
        resolve: (result) => {
          clearTimeout(timeout)
          resolve(result)
        },
        reject: (error) => {
          clearTimeout(timeout)
          reject(error)
        },
      })
      this.#socket.send(JSON.stringify({ id, method, params }))
    })
  }

  close() {
    this.#socket.close()
  }
}

async function waitForDevToolsPort() {
  const portFile = join(profile, 'DevToolsActivePort')
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    if (chrome.exitCode !== null) throw new Error(`Chrome exited early with code ${chrome.exitCode}`)
    if (existsSync(portFile)) return Number(readFileSync(portFile, 'utf8').split('\n')[0])
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error('Timed out waiting for Chrome DevTools to start.')
}

async function evaluate(client, expression) {
  const result = await client.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true,
  })
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
  }
  return result.result.value
}

let client
let browserClient
try {
  const port = await waitForDevToolsPort()
  const versionResponse = await fetch(`http://127.0.0.1:${port}/json/version`)
  if (!versionResponse.ok) throw new Error(`Chrome DevTools returned HTTP ${versionResponse.status}`)
  const version = await versionResponse.json()
  const targetsResponse = await fetch(`http://127.0.0.1:${port}/json/list`)
  if (!targetsResponse.ok) throw new Error(`Chrome DevTools returned HTTP ${targetsResponse.status}`)
  const targets = await targetsResponse.json()
  const page = targets.find((target) => target.type === 'page' && target.url.startsWith(uiUrl))
  if (!page) throw new Error('Chrome did not open the PatchPilot page.')

  browserClient = new DevToolsClient(version.webSocketDebuggerUrl)
  client = new DevToolsClient(page.webSocketDebuggerUrl)
  await client.send('Page.enable')
  await client.send('Runtime.enable')
  const formReady = await evaluate(client, `new Promise((resolve) => {
    const deadline = Date.now() + 15000;
    const poll = setInterval(() => {
      if (document.querySelector('form')) {
        clearInterval(poll);
        resolve({ ready: true });
      } else if (Date.now() >= deadline) {
        clearInterval(poll);
        resolve({ ready: false, url: location.href, text: document.body.innerText });
      }
    }, 100);
  })`)
  if (!formReady.ready) throw new Error(`Review form did not mount at ${formReady.url}. Page text: ${formReady.text}`)

  const titleValue = JSON.stringify(title)
  const diffValue = JSON.stringify(diffText)
  const formResult = await evaluate(client, `(() => {
    const titleInput = document.querySelector('input[name="title"]');
    const diffInput = document.querySelector('textarea[name="diffText"]');
    const form = document.querySelector('form');
    if (!titleInput || !diffInput || !form) return 'review form not found';
    titleInput.value = ${titleValue};
    titleInput.dispatchEvent(new Event('input', { bubbles: true }));
    diffInput.value = ${diffValue};
    diffInput.dispatchEvent(new Event('input', { bubbles: true }));
    form.requestSubmit();
    return 'submitted';
  })()`)
  if (formResult !== 'submitted') throw new Error(formResult)

  const result = await evaluate(client, `new Promise((resolve) => {
    const expectedTitle = ${titleValue};
    const deadline = Date.now() + 60000;
    const poll = setInterval(() => {
      const text = document.body.innerText;
      const finding = document.querySelector('.finding-item');
      if (text.includes(expectedTitle) && finding && text.includes('Potential null dereference')) {
        clearInterval(poll);
        resolve({ ok: true, text });
      } else if (text.includes(expectedTitle) && text.includes('审查未能完成')) {
        clearInterval(poll);
        resolve({ ok: false, text });
      } else if (Date.now() >= deadline) {
        clearInterval(poll);
        resolve({ ok: false, text });
      }
    }, 300);
  })`)

  if (!result.ok) {
    throw new Error(`Review did not display the expected finding. Page text:\n${result.text.slice(-3000)}`)
  }
  for (const expected of ['已完成', 'The new rename method dereferences name without checking for null.', 'Potential null dereference', 'src/Account.java']) {
    if (!result.text.includes(expected)) throw new Error(`Review result did not contain: ${expected}`)
  }
  console.log(`Browser E2E passed: ${title}`)
  console.log('Displayed completed review with HIGH NULL_DEREFERENCE at src/Account.java:2.')
} finally {
  if (browserClient) await browserClient.send('Browser.close').catch(() => {})
  client?.close()
  browserClient?.close()
  if (chrome.exitCode === null) {
    chrome.kill('SIGTERM')
    await Promise.race([
      new Promise((resolve) => chrome.once('exit', resolve)),
      new Promise((resolve) => setTimeout(resolve, 3000)),
    ])
    if (chrome.exitCode === null) {
      chrome.kill('SIGKILL')
      await Promise.race([
        new Promise((resolve) => chrome.once('exit', resolve)),
        new Promise((resolve) => setTimeout(resolve, 2000)),
      ])
    }
  }
  rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 })
}
