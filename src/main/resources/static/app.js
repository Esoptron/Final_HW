(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const date = $('date');
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = key => parts.find(item => item.type === key).value;
  const today = `${part('year')}-${part('month')}-${part('day')}`;
  date.value = today;
  date.max = today;
  let version = 0;
  let pollTimer;
  let collecting = false;
  let loadSequence = 0;
  const decimal = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 4, maximumFractionDigits: 6 });
  const percent = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const formatDate = value => value ? new Date(value + 'T12:00:00').toLocaleDateString('ru-RU') : '-';
  const signed = (value, formatter) => (value > 0 ? '+' : '') + formatter.format(value);

  function notice(kind, title, detail) {
    $('notice').className = `notice ${kind}`;
    $('notice-title').textContent = title;
    $('notice-detail').textContent = detail;
  }

  function busy(value) {
    collecting = value;
    $('collect').disabled = value;
    $('collect').textContent = value ? 'Загружаем…' : 'Загрузить курсы';
  }

  async function request(url, options = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(url, { ...options, signal: controller.signal, cache: 'no-store' });
      if (response.status === 204) return null;
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || `Ошибка сервера: ${response.status}`);
      $('connection').textContent = 'Сервис работает';
      $('connection').className = 'badge online';
      return body;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('Сервис долго не отвечает. Проверьте, что приложение запущено в IDE.');
      if (error instanceof TypeError) throw new Error('Нет связи с приложением. Запустите CurrencyRatesApplication и обновите страницу.');
      throw error;
    } finally { clearTimeout(timer); }
  }

  function cell(row, value, className) {
    const td = document.createElement('td');
    td.textContent = value;
    td.className = className || '';
    row.append(td);
    return td;
  }

  function render(page) {
    $('rates').replaceChildren();
    if (page.content.length === 0) {
      const row = document.createElement('tr');
      const empty = cell(row, 'За выбранную дату курсы ещё не сохранены. Нажмите «Загрузить курсы».', 'empty');
      empty.colSpan = 5;
      $('rates').append(row);
    }
    const symbols = { USD: '$', EUR: '€', CNY: '¥', GBP: '£', JPY: '¥' };
    for (const rate of page.content) {
      const row = document.createElement('tr');
      const currency = cell(row, '');
      const wrapper = document.createElement('div');
      wrapper.className = 'currency';
      const symbol = document.createElement('span');
      symbol.className = 'currency-symbol';
      symbol.textContent = symbols[rate.currency] || rate.currency;
      symbol.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      const code = document.createElement('span');
      code.className = 'currency-code';
      code.textContent = rate.currency;
      const name = document.createElement('span');
      name.className = 'currency-name';
      name.textContent = rate.name;
      label.append(code, name);
      wrapper.append(symbol, label);
      currency.append(wrapper);
      cell(row, decimal.format(rate.rate), 'number rate');
      const trend = rate.change > 0 ? 'up' : rate.change < 0 ? 'down' : 'neutral';
      cell(row, signed(rate.change, decimal), `number ${trend}`);
      cell(row, signed(rate.changePercent, percent) + '%', `number ${trend}`);
      cell(row, formatDate(rate.rateDate), 'rate-date');
      $('rates').append(row);
    }
    $('count').textContent = `Дата: ${formatDate(date.value)} · Валют в таблице: ${page.totalElements}`;
    const collectedAt = page.content[0]?.collectedAt;
    const sourceUrl = page.content[0]?.sourceUrl;
    if (sourceUrl) {
      const isCbr = new URL(sourceUrl).hostname === 'www.cbr.ru';
      $('source').textContent = isCbr ? 'Банк России' : 'Currency API (резервный источник)';
      $('source').href = isCbr ? 'https://www.cbr.ru/development/SXML/' : 'https://github.com/fawazahmed0/exchange-api';
    } else {
      $('source').textContent = 'Банк России / Currency API';
      $('source').href = 'https://www.cbr.ru/development/SXML/';
    }
    $('updated').textContent = collectedAt ? 'Получено: ' + new Date(collectedAt).toLocaleString('ru-RU') : 'Нет сохранённых данных за эту дату';
  }

  async function load(expected = version) {
    const sequence = ++loadSequence;
    const params = new URLSearchParams({ date: date.value, sortBy: $('sort').value, direction: $('direction').value });
    if ($('currency').value) params.set('currency', $('currency').value);
    const page = await request('/api/rates?' + params);
    if (expected === version && sequence === loadSequence) render(page);
    return page;
  }

  async function watch(job, expected, attempts = 0) {
    if (expected !== version) return;
    if (job.status === 'COMPLETED') {
      busy(false);
      await load(expected);
      if (expected !== version) return;
      notice('success', 'Курсы загружены', `Сохранены ${job.savedRates} валют за ${formatDate(job.date)}. Таблица обновлена.`);
      return;
    }
    if (job.status === 'FAILED') {
      busy(false);
      notice('error', 'Не удалось загрузить курсы', (job.error || 'Источник данных не отвечает.') + '. Проверьте доступ к источнику и повторите загрузку. Сохранённые ранее курсы остаются в таблице.');
      return;
    }
    busy(true);
    notice('loading', 'Получаем курсы валют', `Загружаем данные за ${formatDate(job.date)}. Обычно это занимает несколько секунд.`);
    if (attempts >= 45) {
      busy(false);
      notice('', 'Загрузка занимает больше времени', 'Задача продолжает выполняться. Нажмите «Обновить таблицу», чтобы проверить результат.');
      return;
    }
    pollTimer = setTimeout(async () => {
      try { await watch(await request('/api/collections/' + encodeURIComponent(job.id)), expected, attempts + 1); }
      catch (error) { if (expected === version) { busy(false); notice('error', 'Ошибка соединения', error.message); } }
    }, 1200);
  }

  async function refresh() {
    const expected = ++version;
    clearTimeout(pollTimer);
    busy(false);
    try {
      const page = await load(expected);
      const job = await request('/api/collections/latest');
      if (expected !== version) return;
      if (job && job.date === date.value) await watch(job, expected);
      else if (page.totalElements > 0) notice('success', 'Показаны сохранённые курсы', 'Нажмите «Загрузить курсы», чтобы получить данные из источника повторно.');
      else notice('', 'За эту дату пока нет курсов', 'Выберите дату и нажмите «Загрузить курсы».');
    } catch (error) {
      if (expected !== version) return;
      notice('error', 'Не удалось получить данные', error.message);
      $('connection').textContent = 'Ошибка подключения';
      $('connection').className = 'badge';
    }
  }

  $('collect').addEventListener('click', async () => {
    if (collecting || !date.reportValidity()) return;
    const expected = ++version;
    clearTimeout(pollTimer);
    busy(true);
    notice('loading', 'Запускаем загрузку', 'Подготавливаем запрос на выбранную дату.');
    try {
      const job = await request('/api/collections', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ date: date.value }) });
      await watch(job, expected);
    } catch (error) {
      if (expected === version) { busy(false); notice('error', 'Не удалось начать загрузку', error.message); }
    }
  });
  $('refresh').addEventListener('click', refresh);
  date.addEventListener('change', () => { if (date.reportValidity()) refresh(); });
  for (const id of ['currency', 'sort', 'direction']) {
    $(id).addEventListener('change', async () => {
      try { await load(); }
      catch (error) { notice('error', 'Не удалось обновить таблицу', error.message); }
    });
  }
  refresh();
})();
