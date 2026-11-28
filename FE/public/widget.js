(() => {
  const script = document.currentScript;
  if (!script || script.dataset.blueAiMounted === 'true') return;
  script.dataset.blueAiMounted = 'true';

  const botKey = script.dataset.botKey;
  if (!botKey) {
    console.error('[BlueAI Widget] Missing data-bot-key.');
    return;
  }

  const apiBase = (script.dataset.apiBase || new URL(script.src, window.location.href).origin).replace(/\/$/, '');
  const primaryColor = script.dataset.primaryColor || '#1e4fd8';
  const title = script.dataset.title || 'Trợ lý BlueAI';
  const mount = document.createElement('div');
  mount.setAttribute('data-blueai-widget', botKey);
  document.body.appendChild(mount);
  const root = mount.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = `
    :host { --blueai-primary: ${primaryColor}; color: #0f172a; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    * { box-sizing: border-box; }
    button, input { font: inherit; }
    .launcher { position: fixed; right: 22px; bottom: 22px; z-index: 2147483000; width: 58px; height: 58px; border: 0; border-radius: 20px; color: white; background: var(--blueai-primary); box-shadow: 0 18px 45px rgba(15, 23, 42, .28); cursor: pointer; display: grid; place-items: center; transition: transform .2s ease; }
    .launcher:hover { transform: translateY(-2px); }
    .launcher svg { width: 27px; height: 27px; }
    .panel { position: fixed; right: 22px; bottom: 92px; z-index: 2147483000; width: min(390px, calc(100vw - 24px)); height: min(620px, calc(100vh - 118px)); overflow: hidden; border: 1px solid #dbe4f0; border-radius: 24px; background: white; box-shadow: 0 24px 70px rgba(15, 23, 42, .25); display: none; flex-direction: column; }
    .panel.open { display: flex; animation: blueai-in .18s ease-out; }
    @keyframes blueai-in { from { opacity: 0; transform: translateY(10px) scale(.98); } }
    .header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 18px; color: white; background: linear-gradient(135deg, var(--blueai-primary), #0891b2); }
    .identity { display: flex; align-items: center; gap: 11px; min-width: 0; }
    .avatar { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 14px; background: rgba(255,255,255,.18); display: grid; place-items: center; font-weight: 900; }
    .name { margin: 0; font-size: 14px; font-weight: 800; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .status { margin: 2px 0 0; font-size: 11px; opacity: .8; }
    .close { border: 0; border-radius: 10px; background: transparent; color: white; width: 34px; height: 34px; cursor: pointer; font-size: 24px; line-height: 1; }
    .close:hover { background: rgba(255,255,255,.14); }
    .messages { flex: 1; overflow: auto; padding: 18px 14px; background: #f8fafc; }
    .row { display: flex; margin: 0 0 12px; }
    .row.user { justify-content: flex-end; }
    .bubble { max-width: 84%; padding: 10px 13px; border-radius: 16px; background: white; border: 1px solid #e2e8f0; box-shadow: 0 3px 12px rgba(15,23,42,.04); font-size: 13px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
    .row.user .bubble { color: white; border-color: transparent; background: var(--blueai-primary); border-bottom-right-radius: 5px; }
    .row.assistant .bubble { border-bottom-left-radius: 5px; }
    .composer { display: flex; align-items: end; gap: 8px; padding: 12px; border-top: 1px solid #e2e8f0; background: white; }
    .input { flex: 1; min-width: 0; border: 1px solid #cbd5e1; border-radius: 14px; padding: 10px 12px; color: #0f172a; background: white; outline: none; }
    .input:focus { border-color: var(--blueai-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--blueai-primary) 16%, transparent); }
    .send { width: 41px; height: 41px; flex: 0 0 auto; border: 0; border-radius: 13px; color: white; background: var(--blueai-primary); cursor: pointer; display: grid; place-items: center; }
    .send:disabled { opacity: .45; cursor: wait; }
    .send svg { width: 18px; height: 18px; }
    .notice { padding: 8px 12px; background: #fff7ed; color: #9a3412; border-top: 1px solid #fed7aa; font-size: 11px; display: none; }
    .notice.visible { display: block; }
    @media (max-width: 520px) { .panel { right: 12px; bottom: 82px; height: calc(100vh - 96px); } .launcher { right: 14px; bottom: 14px; } }
    @media (prefers-reduced-motion: reduce) { .panel.open { animation: none; } .launcher { transition: none; } }
  `;
  root.appendChild(style);

  const icon = (path) => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '2');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    const shape = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    shape.setAttribute('d', path);
    svg.appendChild(shape);
    return svg;
  };

  const launcher = document.createElement('button');
  launcher.type = 'button';
  launcher.className = 'launcher';
  launcher.setAttribute('aria-label', `Mở ${title}`);
  launcher.appendChild(icon('M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z'));
  root.appendChild(launcher);

  const panel = document.createElement('section');
  panel.className = 'panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', title);
  root.appendChild(panel);

  const header = document.createElement('header');
  header.className = 'header';
  const identity = document.createElement('div');
  identity.className = 'identity';
  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  avatar.textContent = 'b';
  const identityText = document.createElement('div');
  const name = document.createElement('p');
  name.className = 'name';
  name.textContent = title;
  const status = document.createElement('p');
  status.className = 'status';
  status.textContent = 'Sẵn sàng hỗ trợ';
  identityText.append(name, status);
  identity.append(avatar, identityText);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'close';
  close.setAttribute('aria-label', 'Đóng trợ lý');
  close.textContent = '×';
  header.append(identity, close);
  panel.appendChild(header);

  const messages = document.createElement('div');
  messages.className = 'messages';
  messages.setAttribute('aria-live', 'polite');
  panel.appendChild(messages);

  const notice = document.createElement('div');
  notice.className = 'notice';
  panel.appendChild(notice);

  const form = document.createElement('form');
  form.className = 'composer';
  const input = document.createElement('input');
  input.className = 'input';
  input.type = 'text';
  input.maxLength = 2000;
  input.placeholder = 'Nhập câu hỏi…';
  input.setAttribute('aria-label', 'Tin nhắn');
  const send = document.createElement('button');
  send.className = 'send';
  send.type = 'submit';
  send.setAttribute('aria-label', 'Gửi tin nhắn');
  send.appendChild(icon('m22 2-7 20-4-9-9-4Z M22 2 11 13'));
  form.append(input, send);
  panel.appendChild(form);

  let initialized = false;
  let sessionId;
  let accessToken;
  let busy = false;
  let composing = false;

  input.addEventListener('compositionstart', () => { composing = true; });
  input.addEventListener('compositionend', () => { composing = false; });
  input.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    if (event.shiftKey || composing || event.isComposing || event.keyCode === 229 || event.repeat) return;
    form.requestSubmit();
  });

  const addMessage = (role, text) => {
    const row = document.createElement('div');
    row.className = `row ${role}`;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;
    row.appendChild(bubble);
    messages.appendChild(row);
    messages.scrollTop = messages.scrollHeight;
  };

  const showError = (message) => {
    notice.textContent = message;
    notice.classList.add('visible');
  };

  const initialize = async () => {
    if (initialized || busy) return;
    busy = true;
    send.disabled = true;
    status.textContent = 'Đang kết nối…';
    notice.classList.remove('visible');
    try {
      const response = await fetch(`${apiBase}/api/v1/widget/${encodeURIComponent(botKey)}/sessions`, { method: 'POST' });
      if (!response.ok) throw new Error('Chatbot chưa được phát hành hoặc tạm thời không khả dụng.');
      const session = await response.json();
      sessionId = session.sessionId;
      accessToken = session.accessToken;
      initialized = true;
      status.textContent = 'Sẵn sàng hỗ trợ';
      if (session.welcome && session.welcome.message) addMessage('assistant', session.welcome.message);
    } catch (error) {
      status.textContent = 'Không thể kết nối';
      showError(error instanceof Error ? error.message : 'Không thể khởi tạo phiên chat.');
    } finally {
      busy = false;
      send.disabled = false;
    }
  };

  launcher.addEventListener('click', () => {
    panel.classList.toggle('open');
    const open = panel.classList.contains('open');
    launcher.setAttribute('aria-label', open ? `Đóng ${title}` : `Mở ${title}`);
    if (open) {
      void initialize();
      window.setTimeout(() => input.focus(), 0);
    }
  });
  close.addEventListener('click', () => {
    panel.classList.remove('open');
    launcher.focus();
  });

  // A failed network read does not prove that the server did not process the
  // request. Retry once with the same requestId so the backend can safely
  // return its idempotent result instead of spending another LLM call.
  const sendRuntimeMessage = async (requestId, message) => {
    const url = `${apiBase}/api/v1/runtime/sessions/${encodeURIComponent(sessionId)}/messages`;
    const options = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Widget-Token': accessToken },
      body: JSON.stringify({ requestId, message }),
    };
    try {
      return await fetch(url, options);
    } catch (firstError) {
      if (!(firstError instanceof TypeError)) throw firstError;
      await new Promise((resolve) => window.setTimeout(resolve, 500));
      return fetch(url, options);
    }
  };

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const message = input.value.trim();
    if (!message || busy) return;
    if (!initialized) await initialize();
    if (!initialized || !sessionId || !accessToken) return;

    input.value = '';
    addMessage('user', message);
    busy = true;
    send.disabled = true;
    notice.classList.remove('visible');
    status.textContent = 'Đang trả lời…';
    try {
      const requestId = window.crypto && window.crypto.randomUUID
        ? window.crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
      const response = await sendRuntimeMessage(requestId, message);
      if (!response.ok && response.status === 429) {
        const retryAfter = Number.parseInt(response.headers.get('Retry-After') || '60', 10);
        const seconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 60;
        throw new Error(`Bạn đang gửi tin nhắn quá nhanh. Vui lòng thử lại sau ${seconds} giây.`);
      }
      if (!response.ok) throw new Error(response.status === 401 ? 'Phiên chat đã hết hạn. Hãy tải lại trang.' : 'Không thể gửi tin nhắn.');
      const payload = await response.json();
      addMessage('assistant', payload.result.message);
      status.textContent = 'Sẵn sàng hỗ trợ';
    } catch (error) {
      status.textContent = 'Có lỗi kết nối';
      showError(error instanceof Error ? error.message : 'Không thể gửi tin nhắn.');
    } finally {
      busy = false;
      send.disabled = false;
      input.focus();
    }
  });
})();
