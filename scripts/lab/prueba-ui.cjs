const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

// Prueba de interfaz sin guardar estrategias ni ejecutar RMAN.
(async () => {
  const base = process.env.APP_URL || 'http://127.0.0.1:8787';
  const output = path.resolve('runtime/pruebas-ui', new Date().toISOString().replace(/[:.]/g, '-'));
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const form = page.locator('#strategy-form');
  const errors = [], results = [];
  const check = (name, condition) => { assert.ok(condition, name); results.push(name); };
  page.on('pageerror', e => errors.push(e.message));
  let releaseOld = () => {};
  try {
    await page.goto(base, { waitUntil: 'networkidle' });
    await page.locator('#health-body tr').first().waitFor();
    const state = await (await page.request.get(base + '/api/state')).json();
    check('Modo LOCAL', state.mode === 'LOCAL');
    const db = state.databases.find(d => d.container === 'rman-lab');
    const file = state.statuses[db.id].datafiles.find(d => d.tablespace === 'LAB_DATOS');
    check('Objeto real disponible', !!file);
    await page.locator('#new-button').click();
    await form.locator('[name="name"]').fill('QA_UI_SIN_GUARDAR');
    await form.locator('[name="databaseId"]').selectOption(db.id);
    await form.locator('[name="scope"][value="DATAFILE"]').check();
    await form.locator('[name="datafiles"]').fill(String(file.file));
    await page.waitForFunction(() => document.querySelector('#live-script').textContent.includes('BACKUP'));
    check('Vista valida con huella', (await page.locator('#preview-hash').innerText()).includes('huella'));
    await form.locator('[name="datafiles"]').fill('65533');
    await page.waitForFunction(() => document.querySelector('#live-script').textContent.includes('bloqueado'));
    check('Error semantico oculta script', !(await page.locator('#live-script').innerText()).includes('BACKUP'));
    check('Error semantico borra huella', await page.locator('#preview-hash').innerText() === '');
    await page.screenshot({ path: path.join(output, 'validacion-bloqueada.png'), fullPage: true });
    await form.locator('[name="datafiles"]').fill('abc');
    await page.waitForFunction(() => document.querySelector('#live-script').textContent.includes('no disponible'));
    check('Error sintactico no conserva script anterior', await page.locator('#preview-hash').innerText() === '');
    await form.locator('[name="datafiles"]').fill(String(file.file));
    await page.waitForFunction(() => document.querySelector('#live-script').textContent.includes('BACKUP'));

    let oldSeen, oldFinished;
    const seen = new Promise(resolve => { oldSeen = resolve; });
    const finished = new Promise(resolve => { oldFinished = resolve; });
    const held = new Promise(resolve => { releaseOld = resolve; });
    await page.route('**/api/preview', async route => {
      const name = route.request().postDataJSON().strategy.name;
      if (name !== 'QA_RACE_OLD') return route.continue();
      const response = await route.fetch();
      oldSeen();
      await held;
      await route.fulfill({ response });
      oldFinished();
    });
    await form.locator('[name="name"]').fill('QA_RACE_OLD');
    await Promise.race([seen, new Promise((_, reject) => setTimeout(() => reject(Error('No llego la peticion controlada')), 10000))]);
    await form.locator('[name="name"]').fill('QA_RACE_NEW');
    await page.waitForFunction(() => document.querySelector('#live-script').textContent.includes('QA_RACE_NEW'));
    const hash = await page.locator('#preview-hash').innerText();
    releaseOld();
    await finished;
    await page.waitForLoadState('networkidle');
    check('Respuesta antigua descartada', (await page.locator('#live-script').innerText()).includes('QA_RACE_NEW'));
    check('Huella pertenece a la ultima configuracion', await page.locator('#preview-hash').innerText() === hash);
    await page.unroute('**/api/preview');
    await page.setViewportSize({ width: 390, height: 844 });
    check('Formulario movil sin desbordamiento horizontal', await page.locator('#strategy-dialog').evaluate(e => e.scrollWidth <= e.clientWidth + 1));
    await page.screenshot({ path: path.join(output, 'constructor-movil.png'), fullPage: true });
    await page.locator('#strategy-dialog .dialog-footer .close-dialog').click();
    await page.locator('#strategy-dialog').waitFor({ state: 'hidden' });
    check('Cancelar cierra el formulario sin guardar', true);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.locator('[data-view="executions"]').click();
    const qa = state.executions.find(e => e.strategyName.startsWith('QA_LOCAL_') && e.operation === 'BACKUP' && e.evidence.verified);
    check('Historial contiene respaldo verificado', !!qa);
    await page.locator(`#executions-body button[data-id="${qa.id}"]`).click();
    await page.locator('#detail-dialog[open]').waitFor();
    await page.locator('#show-verify').click();
    check('Evidencia RMAN visible', (await page.locator('#detail-dialog').innerText()).includes('VALIDATE BACKUPSET'));
    check('Sin errores JavaScript', errors.length === 0);
    console.log('PRUEBA_UI_OK', results.length, output);
  } finally {
    releaseOld();
    await fs.writeFile(path.join(output, 'resultados.json'), JSON.stringify({ checks: results, errors }, null, 2));
    await browser.close();
  }
})().catch(e => { console.error(e); process.exitCode = 1; });
