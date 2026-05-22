const state = {
  token: localStorage.getItem('chatToken'),
  user: JSON.parse(localStorage.getItem('chatUser') || 'null'),
  conversations: [],
  activeConversationId: null,
  ws: null,
};

const authPanel = document.getElementById('auth-panel');
const chatPanel = document.getElementById('chat-panel');
const chatMain = document.getElementById('chat-main');
const currentUserEl = document.getElementById('current-user');
const conversationList = document.getElementById('conversation-list');
const messagesEl = document.getElementById('messages');
const chatHeader = document.getElementById('chat-header');

function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  return fetch(path, { ...options, headers }).then(async (res) => {
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.message || `Request failed (${res.status})`);
    }
    return res.status === 204 ? null : res.json();
  });
}

function setAuth(user, token) {
  state.user = user;
  state.token = token;
  localStorage.setItem('chatUser', JSON.stringify(user));
  localStorage.setItem('chatToken', token);
  authPanel.classList.add('hidden');
  chatPanel.classList.remove('hidden');
  chatMain.classList.remove('hidden');
  currentUserEl.textContent = `${user.displayName} (@${user.username})`;
  connectWebSocket();
  loadConversations();
}

function logout() {
  state.token = null;
  state.user = null;
  localStorage.removeItem('chatToken');
  localStorage.removeItem('chatUser');
  if (state.ws) state.ws.close();
  location.reload();
}

async function register() {
  const payload = {
    username: document.getElementById('reg-username').value.trim(),
    displayName: document.getElementById('reg-display').value.trim(),
    email: document.getElementById('reg-email').value.trim(),
    password: document.getElementById('reg-password').value,
  };
  const res = await api('/api/v1/auth/register', { method: 'POST', body: JSON.stringify(payload) });
  setAuth(res, res.token);
}

async function login() {
  const payload = {
    login: document.getElementById('login-input').value.trim(),
    password: document.getElementById('password-input').value,
  };
  const res = await api('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(payload) });
  setAuth(res, res.token);
}

function connectWebSocket() {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  state.ws = new WebSocket(`${protocol}://${location.host}/ws?token=${encodeURIComponent(state.token)}`);
  state.ws.onmessage = (event) => {
    const frame = JSON.parse(event.data);
    if (frame.type === 'NEW_MESSAGE') {
      if (frame.payload.conversation_id === state.activeConversationId) {
        appendMessage(frame.payload, false);
      }
      loadConversations();
    }
  };
}

async function loadConversations() {
  const res = await api('/api/v1/conversations?limit=50');
  state.conversations = res.conversations;
  conversationList.innerHTML = '';
  res.conversations.forEach((c) => {
    const li = document.createElement('li');
    li.textContent = c.name || `${c.type} (${c.conversationId.slice(0, 8)}...)`;
    li.onclick = () => openConversation(c.conversationId, li.textContent);
    if (c.conversationId === state.activeConversationId) li.classList.add('active');
    conversationList.appendChild(li);
  });
}

async function openConversation(conversationId, title) {
  state.activeConversationId = conversationId;
  chatHeader.textContent = title;
  messagesEl.innerHTML = '';
  const res = await api(`/api/v1/conversations/${conversationId}/messages?limit=50`);
  res.messages.slice().reverse().forEach((m) => appendMessage(m, m.senderId === state.user.userId));
  loadConversations();
}

function appendMessage(message, mine) {
  const div = document.createElement('div');
  div.className = `message${mine ? ' mine' : ''}`;
  div.textContent = message.content;
  messagesEl.appendChild(div);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

async function createConversation() {
  const type = document.getElementById('conversation-type').value;
  const memberIds = document.getElementById('member-ids').value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const payload = {
    type,
    name: document.getElementById('group-name').value.trim() || null,
    memberIds,
  };
  const res = await api('/api/v1/conversations', { method: 'POST', body: JSON.stringify(payload) });
  await loadConversations();
  openConversation(res.conversationId, res.name || res.type);
}

function sendMessage(content) {
  if (!state.activeConversationId || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const frame = {
    frame_id: crypto.randomUUID(),
    type: 'SEND_MESSAGE',
    payload: {
      conversation_id: state.activeConversationId,
      idempotency_key: crypto.randomUUID(),
      content_type: 'TEXT',
      content,
    },
  };
  state.ws.send(JSON.stringify(frame));
  appendMessage({ content }, true);
}

document.getElementById('login-btn').onclick = () => login().catch(alert);
document.getElementById('register-btn').onclick = () => register().catch(alert);
document.getElementById('logout-btn').onclick = logout;
document.getElementById('create-conversation-btn').onclick = () => createConversation().catch(alert);
document.getElementById('message-form').onsubmit = (e) => {
  e.preventDefault();
  const input = document.getElementById('message-input');
  const value = input.value.trim();
  if (!value) return;
  sendMessage(value);
  input.value = '';
};

if (state.token && state.user) {
  setAuth(state.user, state.token);
}
