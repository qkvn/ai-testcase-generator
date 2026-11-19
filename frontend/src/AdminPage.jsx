import { useCallback, useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import LoginModal from './LoginModal';
import { adminText } from './adminText';

// ======================================================================
// TRANG QUẢN TRỊ (/admin) — chỉ tài khoản có quyền ADMIN mới xem được dữ liệu
// ======================================================================

const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';
const api = axios.create({ baseURL: API_BASE_URL, withCredentials: true });

// Chạy hàm tải dữ liệu ở lượt kế tiếp (không setState đồng bộ trong useEffect)
const defer = (fn) => { queueMicrotask(fn); };

const errMsg = (err, T) => {
  const d = err?.response?.data;
  return (d && (d.message || (typeof d === 'string' ? d : ''))) || err?.message || T.error;
};

const fmtTime = (ms, language) => ms ? new Date(ms).toLocaleString(language === 'en' ? 'en-US' : 'vi-VN', {
  day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
}) : '—';

const ago = (ms, T) => {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  return s < 60 ? T.secondsAgo(s) : T.minutesAgo(Math.round(s / 60));
};

function Avatar({ user, size = 32 }) {
  const label = (user?.name || user?.username || user?.email || '?').trim();
  if (user?.picture) {
    return <img src={user.picture} alt="" referrerPolicy="no-referrer" style={{ width: size, height: size, borderRadius: '50%', flexShrink: 0, objectFit: 'cover' }} />;
  }
  const colors = ['#1f4fd8', '#7b4fe0', '#ff8a00', '#15803d', '#0891b2', '#db2777'];
  const color = colors[(label.charCodeAt(0) || 0) % colors.length];
  return (
    <div style={{ width: size, height: size, borderRadius: '50%', background: color, color: '#fff', fontWeight: 700, fontSize: size * 0.42, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      {label.charAt(0).toUpperCase()}
    </div>
  );
}

function UserCell({ user, T, me, showEmail }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 }}>
      <Avatar user={user} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {user.name || user.username || user.email}
          {me && me.id === user.id && <span className="ad-badge ad-badge-blue" style={{ marginLeft: 6 }}>{T.you}</span>}
        </div>
        <div style={{ fontSize: '12px', color: '#64748b', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {[user.username && '@' + user.username, showEmail && user.email].filter(Boolean).join(' · ') || user.email}
        </div>
      </div>
    </div>
  );
}

// ---------------- Biểu đồ cột (SVG) ----------------
function BarChart({ data, language }) {
  const W = 720, H = 230, padL = 34, padB = 34, padT = 20;
  const max = Math.max(1, ...data.map(d => d.count));
  const step = Math.max(1, Math.ceil(max / 4));
  const top = step * 4;
  const bw = (W - padL - 10) / data.length;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }} role="img">
      {[0, 1, 2, 3, 4].map(i => {
        const y = H - padB - (i * step / top) * (H - padB - padT);
        return (
          <g key={i}>
            <line x1={padL} x2={W - 6} y1={y} y2={y} stroke="#eef1f6" />
            <text x={padL - 8} y={y + 4} fontSize="11" fill="#94a3b8" textAnchor="end">{i * step}</text>
          </g>
        );
      })}
      {data.map((d, i) => {
        const h = (d.count / top) * (H - padB - padT);
        const x = padL + i * bw + bw * 0.18;
        const y = H - padB - h;
        const date = new Date(d.date + 'T00:00:00');
        const label = date.toLocaleDateString(language === 'en' ? 'en-US' : 'vi-VN', { day: '2-digit', month: '2-digit' });
        return (
          <g key={d.date}>
            <title>{label}: {d.count}</title>
            <rect x={x} y={y} width={bw * 0.64} height={Math.max(h, d.count ? 2 : 0)} rx="4" fill={i === data.length - 1 ? '#1f4fd8' : '#9db4f0'} />
            {d.count > 0 && <text x={x + bw * 0.32} y={y - 6} fontSize="11" fill="#334155" textAnchor="middle" fontWeight="600">{d.count}</text>}
            <text x={x + bw * 0.32} y={H - padB + 18} fontSize="10.5" fill="#94a3b8" textAnchor="middle">{label}</text>
          </g>
        );
      })}
    </svg>
  );
}

// ---------------- Tổng quan ----------------
function Overview({ T, language, stats }) {
  if (!stats) return <div className="ad-empty">{T.loading}</div>;
  const cards = [
    ['onlineNow', '🟢', '#15803d'], ['totalUsers', '👥', '#1f4fd8'], ['newToday', '✨', '#7b4fe0'], ['new7Days', '📅', '#0891b2'],
    ['totalSessions', '⚡', '#ff8a00'], ['totalTestCases', '🧪', '#1f4fd8'], ['totalRequirements', '🔗', '#7b4fe0'], ['lockedUsers', '🔒', '#dc2626'],
  ];
  const total = stats.totalUsers || 0;
  const gPct = total ? Math.round((stats.googleUsers / total) * 100) : 0;
  return (
    <div>
      <div className="ad-cards">
        {cards.map(([key, icon, color]) => (
          <div key={key} className="ad-card ad-stat">
            <div className="ad-stat-icon" style={{ background: color + '14', color }}>{icon}</div>
            <div>
              <div className="ad-stat-value">{stats[key] ?? 0}</div>
              <div className="ad-stat-label">{T.stats[key]}</div>
            </div>
          </div>
        ))}
      </div>

      <div className="ad-grid-2">
        <div className="ad-card">
          <div className="ad-card-title">{T.chartTitle}</div>
          <BarChart data={stats.generationsPerDay || []} language={language} />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className="ad-card">
            <div className="ad-card-title">{T.accountTypes}</div>
            <div className="ad-split">
              <div style={{ width: gPct + '%', background: '#1f4fd8' }} />
              <div style={{ width: (100 - gPct) + '%', background: '#ff8a00' }} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', marginTop: '10px', color: '#475569' }}>
              <span><span className="ad-dot" style={{ background: '#1f4fd8' }} /> {T.googleAccounts}: <b>{stats.googleUsers}</b></span>
              <span><span className="ad-dot" style={{ background: '#ff8a00' }} /> {T.localAccounts}: <b>{stats.localUsers}</b></span>
            </div>
          </div>
          <div className="ad-card" style={{ flex: 1 }}>
            <div className="ad-card-title">{T.topUsers}</div>
            {(stats.topUsers || []).length === 0 ? <div className="ad-muted">{T.noData}</div> : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {stats.topUsers.map((u, i) => (
                  <div key={u.id} style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <span style={{ width: 18, color: '#94a3b8', fontWeight: 700, fontSize: 13 }}>{i + 1}</span>
                    <div style={{ flex: 1, minWidth: 0 }}><UserCell user={u} T={T} /></div>
                    <span className="ad-badge">{u.sessions} {T.sessionsUnit}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------- Đang online ----------------
function OnlineUsers({ T, me, notify, refreshKey, onChanged }) {
  const [rows, setRows] = useState(null);
  const [, setTick] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/online');
      setRows(res.data);
    } catch (err) { notify(errMsg(err, T), 'error'); }
  }, [notify, T]);

  useEffect(() => {
    defer(load);
    const timer = setInterval(() => { load(); setTick(t => t + 1); }, 15000);
    return () => clearInterval(timer);
  }, [load, refreshKey]);

  const kick = async (u) => {
    if (!window.confirm(T.forceLogoutConfirm(u.name || u.username || u.email))) return;
    try {
      await api.post(`/api/admin/users/${u.id}/force-logout`);
      notify(T.forceLogoutDone);
      load(); onChanged();
    } catch (err) { notify(errMsg(err, T), 'error'); }
  };

  return (
    <div className="ad-card">
      <p className="ad-muted" style={{ marginTop: 0 }}>{T.onlineDesc}</p>
      {rows === null ? <div className="ad-empty">{T.loading}</div> : rows.length === 0 ? <div className="ad-empty">🌙 {T.nobodyOnline}</div> : (
        <div className="ad-table-wrap">
          <table className="ad-table">
            <thead><tr><th>{T.col.account}</th><th>{T.col.email}</th><th>{T.col.type}</th><th>{T.lastActive}</th><th>{T.tabsCount}</th><th></th></tr></thead>
            <tbody>
              {rows.map(u => (
                <tr key={u.id}>
                  <td><div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><span className="ad-dot ad-pulse" style={{ background: '#22c55e' }} /><UserCell user={u} T={T} me={me} /></div></td>
                  <td>{u.email || '—'}</td>
                  <td><TypeBadge u={u} T={T} /></td>
                  <td>{ago(u.lastAccess, T)}</td>
                  <td>{u.sessions}</td>
                  <td style={{ textAlign: 'right' }}>
                    {me?.id !== u.id && <button className="ad-btn ad-btn-danger-soft" onClick={() => kick(u)}>⏏ {T.forceLogout}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TypeBadge({ u, T }) {
  return u.provider === 'google'
    ? <span className="ad-badge ad-badge-blue">G {T.typeGoogle}</span>
    : <span className="ad-badge">👤 {T.typeLocal}</span>;
}

// ---------------- Modal chung ----------------
function Modal({ title, onClose, children, width = 460 }) {
  return (
    <div className="ad-overlay" onClick={onClose}>
      <div className="ad-modal" style={{ maxWidth: width }} onClick={(e) => e.stopPropagation()}>
        <div className="ad-modal-head">
          <h3>{title}</h3>
          <button className="ad-icon-btn" onClick={onClose} aria-label="close">✕</button>
        </div>
        {children}
      </div>
    </div>
  );
}

function ResetPasswordModal({ T, user, requestId, onClose, onDone, notify }) {
  const [pw, setPw] = useState('');
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const res = requestId
        ? await api.post(`/api/admin/reset-requests/${requestId}/resolve`)
        : await api.post(`/api/admin/users/${user.id}/reset-password`, { newPassword: pw || null });
      setResult(res.data);
      onDone();
    } catch (err) { notify(errMsg(err, T), 'error'); }
    finally { setBusy(false); }
  };

  const copy = async () => {
    try { await navigator.clipboard.writeText(result.password); setCopied(true); } catch { /* bỏ qua */ }
  };

  return (
    <Modal title={'🔑 ' + T.resetTitle} onClose={onClose}>
      {result ? (
        <div>
          <div className="ad-muted" style={{ marginBottom: 8 }}>{T.resetDone} <b>@{result.username}</b>:</div>
          <div className="ad-secret">
            <code>{result.password}</code>
            <button className="ad-btn ad-btn-primary" onClick={copy}>{copied ? T.copied : T.copy}</button>
          </div>
          <p className="ad-note">{T.pwSecurityNote}</p>
          <div className="ad-modal-foot"><button className="ad-btn" onClick={onClose}>OK</button></div>
        </div>
      ) : (
        <form onSubmit={submit}>
          {user && <div style={{ marginBottom: 12 }}><UserCell user={user} T={T} /></div>}
          <p className="ad-muted" style={{ marginTop: 0 }}>{T.resetDesc}</p>
          {!requestId && (
            <>
              <label className="ad-label">{T.newPassword}</label>
              <input className="ad-input" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="••••••" />
            </>
          )}
          <p className="ad-note">{T.pwSecurityNote}</p>
          <div className="ad-modal-foot">
            <button type="button" className="ad-btn" onClick={onClose}>{T.cancel}</button>
            <button type="submit" className="ad-btn ad-btn-primary" disabled={busy}>{busy ? T.loading : T.doReset}</button>
          </div>
        </form>
      )}
    </Modal>
  );
}

function UserFormModal({ T, user, onClose, onSaved, notify }) {
  const isNew = !user;
  const [form, setForm] = useState({
    username: user?.username || '', name: user?.name || '', email: user?.email || '',
    role: user?.role || 'USER', locked: !!user?.locked, password: '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      if (isNew) await api.post('/api/admin/users', form);
      else await api.put(`/api/admin/users/${user.id}`, form);
      notify(isNew ? T.created : T.saved);
      onSaved();
      onClose();
    } catch (err) { setError(errMsg(err, T)); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={(isNew ? '➕ ' : '✏️ ') + (isNew ? T.createTitle : T.editTitle)} onClose={onClose}>
      <form onSubmit={submit}>
        {!isNew && user.provider === 'google' && <p className="ad-note" style={{ marginTop: 0 }}>ℹ️ {T.googleNote}</p>}
        <label className="ad-label">{T.fUsername}</label>
        <input className="ad-input" value={form.username} onChange={set('username')} autoFocus />
        <label className="ad-label">{T.fName}</label>
        <input className="ad-input" value={form.name} onChange={set('name')} />
        <label className="ad-label">{T.fEmail}</label>
        <input className="ad-input" type="email" value={form.email} onChange={set('email')} />
        {isNew && (
          <>
            <label className="ad-label">{T.fPassword}</label>
            <input className="ad-input" value={form.password} onChange={set('password')} placeholder="••••••" />
          </>
        )}
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div>
            <label className="ad-label">{T.fRole}</label>
            <select className="ad-input" value={form.role} onChange={set('role')} style={{ width: 'auto' }}>
              <option value="USER">{T.roleUser}</option>
              <option value="ADMIN">{T.roleAdmin}</option>
            </select>
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, paddingBottom: 10, cursor: 'pointer' }}>
            <input type="checkbox" checked={form.locked} onChange={set('locked')} /> {T.fLocked}
          </label>
        </div>
        {error && <div className="ad-error">{error}</div>}
        <div className="ad-modal-foot">
          <button type="button" className="ad-btn" onClick={onClose}>{T.cancel}</button>
          <button type="submit" className="ad-btn ad-btn-primary" disabled={busy}>{busy ? T.loading : (isNew ? T.create : T.save)}</button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------- Quản lý tài khoản ----------------
function Users({ T, language, me, notify, refreshKey, onChanged }) {
  const [rows, setRows] = useState(null);
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState('all');
  const [editing, setEditing] = useState(null);   // user | 'new' | null
  const [resetting, setResetting] = useState(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/users');
      setRows(res.data);
    } catch (err) { notify(errMsg(err, T), 'error'); }
  }, [notify, T]);

  useEffect(() => { defer(load); }, [load, refreshKey]);

  const reload = () => { load(); onChanged(); };

  const filtered = useMemo(() => {
    if (!rows) return [];
    const term = q.trim().toLowerCase();
    return rows.filter(u => {
      if (filter === 'local' && u.provider === 'google') return false;
      if (filter === 'google' && u.provider !== 'google') return false;
      if (filter === 'admin' && u.role !== 'ADMIN') return false;
      if (filter === 'locked' && !u.locked) return false;
      if (filter === 'online' && !u.online) return false;
      if (!term) return true;
      return [u.name, u.username, u.email].some(v => v && v.toLowerCase().includes(term));
    });
  }, [rows, q, filter]);

  const toggleLock = async (u) => {
    try {
      const res = await api.post(`/api/admin/users/${u.id}/toggle-lock`);
      notify(res.data.locked ? T.lockedDone : T.unlockedDone);
      reload();
    } catch (err) { notify(errMsg(err, T), 'error'); }
  };

  const remove = async (u) => {
    if (!window.confirm(T.deleteConfirm(u.name || u.username || u.email))) return;
    try {
      await api.delete(`/api/admin/users/${u.id}`);
      notify(T.deleted);
      reload();
    } catch (err) { notify(errMsg(err, T), 'error'); }
  };

  return (
    <div className="ad-card">
      <div className="ad-toolbar">
        <div className="ad-search">🔍<input value={q} onChange={(e) => setQ(e.target.value)} placeholder={T.search} /></div>
        <select className="ad-input" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ width: 'auto' }}>
          <option value="all">{T.filterAll}</option>
          <option value="online">{T.filterOnline}</option>
          <option value="local">{T.filterLocal}</option>
          <option value="google">{T.filterGoogle}</option>
          <option value="admin">{T.filterAdmin}</option>
          <option value="locked">{T.filterLocked}</option>
        </select>
        <span className="ad-muted" style={{ marginLeft: 'auto' }}>{T.usersCount(filtered.length)}</span>
        <button className="ad-btn ad-btn-primary" onClick={() => setEditing('new')}>{T.createUser}</button>
      </div>

      {rows === null ? <div className="ad-empty">{T.loading}</div> : filtered.length === 0 ? <div className="ad-empty">{T.noUsers}</div> : (
        <div className="ad-table-wrap">
          <table className="ad-table">
            <thead>
              <tr>
                <th>{T.col.account}</th><th>{T.col.type}</th><th>{T.col.role}</th><th>{T.col.status}</th>
                <th>{T.col.created}</th><th>{T.col.lastLogin}</th><th style={{ textAlign: 'right' }}>{T.col.sessions}</th><th style={{ textAlign: 'right' }}>{T.col.testCases}</th><th className="ad-sticky" style={{ textAlign: 'right' }}>{T.col.actions}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(u => {
                const self = me?.id === u.id;
                return (
                  <tr key={u.id} className={u.locked ? 'ad-row-locked' : ''}>
                    <td style={{ maxWidth: 280 }}><UserCell user={u} T={T} me={me} showEmail /></td>
                    <td><TypeBadge u={u} T={T} /></td>
                    <td>{u.role === 'ADMIN' ? <span className="ad-badge ad-badge-purple">🛡️ {T.roleAdmin}</span> : <span className="ad-muted">{T.roleUser}</span>}</td>
                    <td>
                      {u.locked ? <span className="ad-badge ad-badge-red">🔒 {T.statusLocked}</span>
                        : u.online ? <span className="ad-badge ad-badge-green">● {T.statusOnline}</span>
                          : <span className="ad-muted">{T.statusActive}</span>}
                    </td>
                    <td className="ad-nowrap">{fmtTime(u.createdAt, language)}</td>
                    <td className="ad-nowrap">{fmtTime(u.lastLoginAt, language)}</td>
                    <td style={{ textAlign: 'right' }}>{u.sessions}</td>
                    <td style={{ textAlign: 'right' }}>{u.testCases}</td>
                    <td className="ad-sticky">
                      <div className="ad-actions">
                        <button className="ad-icon-btn" title={T.edit} onClick={() => setEditing(u)}>✏️</button>
                        <button className="ad-icon-btn" title={T.resetPw} onClick={() => setResetting(u)}>🔑</button>
                        {!self && <button className="ad-icon-btn" title={u.locked ? T.unlock : T.lock} onClick={() => toggleLock(u)}>{u.locked ? '🔓' : '🔒'}</button>}
                        {!self && <button className="ad-icon-btn ad-icon-danger" title={T.del} onClick={() => remove(u)}>🗑️</button>}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {editing && <UserFormModal T={T} user={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={reload} notify={notify} />}
      {resetting && <ResetPasswordModal T={T} user={resetting} onClose={() => setResetting(null)} onDone={reload} notify={notify} />}
    </div>
  );
}

// ---------------- Nhật ký hoạt động ----------------
const ACTION_COLORS = {
  LOGIN: 'blue', LOGIN_GOOGLE: 'blue', REGISTER: 'green', REGISTER_GOOGLE: 'green', GENERATE: 'orange',
  FORGOT_PASSWORD: 'yellow', ADMIN_LOGIN: 'purple', ADMIN_DELETE_USER: 'red', ADMIN_LOCK_USER: 'red', ADMIN_FORCE_LOGOUT: 'red',
};

function Logs({ T, language, notify, refreshKey }) {
  const [rows, setRows] = useState(null);
  const [action, setAction] = useState('');

  const load = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/logs', { params: action ? { action } : {} });
      setRows(res.data);
    } catch (err) { notify(errMsg(err, T), 'error'); }
  }, [action, notify, T]);

  useEffect(() => { defer(load); }, [load, refreshKey]);

  return (
    <div className="ad-card">
      <div className="ad-toolbar">
        <select className="ad-input" value={action} onChange={(e) => setAction(e.target.value)} style={{ width: 'auto' }}>
          <option value="">{T.allActions}</option>
          {Object.entries(T.actions).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </div>
      {rows === null ? <div className="ad-empty">{T.loading}</div> : rows.length === 0 ? <div className="ad-empty">{T.noLogs}</div> : (
        <div className="ad-table-wrap">
          <table className="ad-table">
            <thead><tr><th>{T.colTime}</th><th>{T.colUser}</th><th>{T.colAction}</th><th>{T.colDetail}</th></tr></thead>
            <tbody>
              {rows.map(l => (
                <tr key={l.id}>
                  <td className="ad-nowrap">{fmtTime(l.createdAt, language)}</td>
                  <td className="ad-nowrap" style={{ fontWeight: 600 }}>{l.username || '—'}</td>
                  <td><span className={'ad-badge ad-badge-' + (ACTION_COLORS[l.action] || (l.action?.startsWith('ADMIN') ? 'purple' : 'gray'))}>{T.actions[l.action] || l.action}</span></td>
                  <td style={{ color: '#475569' }}>{l.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---------------- Yêu cầu quên mật khẩu ----------------
function ResetRequests({ T, language, notify, refreshKey, onChanged }) {
  const [rows, setRows] = useState(null);
  const [handling, setHandling] = useState(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/reset-requests');
      setRows(res.data);
    } catch (err) { notify(errMsg(err, T), 'error'); }
  }, [notify, T]);

  useEffect(() => { defer(load); }, [load, refreshKey]);

  const dismiss = async (r) => {
    try {
      await api.post(`/api/admin/reset-requests/${r.id}/dismiss`);
      notify(T.dismissed);
      load(); onChanged();
    } catch (err) { notify(errMsg(err, T), 'error'); }
  };

  const stColor = { PENDING: 'yellow', RESOLVED: 'green', DISMISSED: 'gray' };

  return (
    <div className="ad-card">
      <p className="ad-muted" style={{ marginTop: 0 }}>{T.resetsDesc}</p>
      {rows === null ? <div className="ad-empty">{T.loading}</div> : rows.length === 0 ? <div className="ad-empty">✅ {T.noResets}</div> : (
        <div className="ad-table-wrap">
          <table className="ad-table">
            <thead><tr><th>{T.colTime}</th><th>{T.colIdentifier}</th><th>{T.colMatched}</th><th>{T.colNote}</th><th>{T.colStatus}</th><th></th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id}>
                  <td className="ad-nowrap">{fmtTime(r.createdAt, language)}</td>
                  <td style={{ fontWeight: 600 }}>{r.identifier}</td>
                  <td>{r.user ? <UserCell user={r.user} T={T} /> : <span className="ad-muted">{T.notMatched}</span>}</td>
                  <td style={{ color: '#475569', maxWidth: 240 }}>{r.note || '—'}</td>
                  <td><span className={'ad-badge ad-badge-' + stColor[r.status]}>{T.st[r.status] || r.status}</span></td>
                  <td>
                    {r.status === 'PENDING' && (
                      <div className="ad-actions">
                        {r.user && <button className="ad-btn ad-btn-primary" onClick={() => setHandling(r)}>🔑 {T.handle}</button>}
                        <button className="ad-btn" onClick={() => dismiss(r)}>{T.dismiss}</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {handling && (
        <ResetPasswordModal T={T} user={handling.user} requestId={handling.id} notify={notify}
          onClose={() => setHandling(null)} onDone={() => { load(); onChanged(); }} />
      )}
    </div>
  );
}

// ---------------- Cài đặt ----------------
function Settings({ T, notify }) {
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    if (form.newPassword !== form.confirm) { setError(T.pwMismatch); return; }
    setBusy(true); setError('');
    try {
      await api.post('/api/admin/me/password', { currentPassword: form.currentPassword, newPassword: form.newPassword });
      notify(T.pwChanged);
      setForm({ currentPassword: '', newPassword: '', confirm: '' });
    } catch (err) { setError(errMsg(err, T)); }
    finally { setBusy(false); }
  };

  return (
    <div className="ad-grid-2">
      <div className="ad-card">
        <div className="ad-card-title">🔐 {T.changePwTitle}</div>
        <form onSubmit={submit} style={{ maxWidth: 380 }}>
          <label className="ad-label">{T.currentPw}</label>
          <input className="ad-input" type="password" value={form.currentPassword} onChange={set('currentPassword')} autoComplete="current-password" />
          <label className="ad-label">{T.newPw}</label>
          <input className="ad-input" type="password" value={form.newPassword} onChange={set('newPassword')} autoComplete="new-password" />
          <label className="ad-label">{T.confirmPw}</label>
          <input className="ad-input" type="password" value={form.confirm} onChange={set('confirm')} autoComplete="new-password" />
          {error && <div className="ad-error">{error}</div>}
          <button type="submit" className="ad-btn ad-btn-primary" style={{ marginTop: 16 }} disabled={busy}>{busy ? T.loading : T.changePw}</button>
        </form>
      </div>
      <div className="ad-card">
        <div className="ad-card-title">🛡️ {T.securityTitle}</div>
        <ul style={{ margin: 0, paddingLeft: 18, color: '#475569', lineHeight: 1.7, fontSize: 14 }}>
          {T.securityTips.map((tip, i) => <li key={i}>{tip}</li>)}
          <li>{T.pwSecurityNote.replace('🔒 ', '')}</li>
        </ul>
      </div>
    </div>
  );
}

// ======================================================================
export default function AdminPage() {
  const [language, setLanguageState] = useState(() => {
    try { return localStorage.getItem('lang') === 'en' ? 'en' : 'vi'; } catch { return 'vi'; }
  });
  const setLanguage = (lang) => {
    setLanguageState(lang);
    try { localStorage.setItem('lang', lang); } catch { /* bỏ qua */ }
  };
  const T = adminText[language] || adminText.vi;

  const [me, setMe] = useState(undefined); // undefined = đang kiểm tra, null = chưa đăng nhập
  const [tab, setTab] = useState('overview');
  const [stats, setStats] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [toast, setToast] = useState(null);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const res = await api.get('/api/user/me');
        if (alive) setMe(res.data);
      } catch {
        if (alive) setMe(null);
      }
    })();
    return () => { alive = false; };
  }, []);

  const isAdmin = me && me.role === 'ADMIN';

  const notify = useCallback((message, type = 'success') => {
    setToast({ message, type, id: Date.now() });
  }, []);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(timer);
  }, [toast]);

  const loadStats = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/stats');
      setStats(res.data);
    } catch (err) {
      if (err.response?.status === 401 || err.response?.status === 403) setMe(null);
    }
  }, []);

  useEffect(() => {
    if (!isAdmin) return undefined;
    defer(loadStats);
    const timer = setInterval(loadStats, 30000);
    return () => clearInterval(timer);
  }, [isAdmin, loadStats, refreshKey]);

  const refresh = () => setRefreshKey(k => k + 1);
  const logout = () => { window.location.href = API_BASE_URL + '/api/logout'; };

  // ---------- Đang kiểm tra ----------
  if (me === undefined) {
    return (
      <div className="ad-center"><style>{adCss}</style><div className="ad-spinner" /><div style={{ marginTop: 14, color: '#64748b' }}>{T.checking}</div></div>
    );
  }

  // ---------- Chưa đăng nhập admin ----------
  if (!isAdmin) {
    return (
      <div className="ad-center ad-login-bg">
        <style>{adCss}</style>
        <div style={{ width: '100%', maxWidth: 400 }}>
          {me && <div className="ad-warn">{T.notAdminNote}</div>}
          <LoginModal inline adminOnly language={language} setLanguage={setLanguage}
            onLoggedIn={() => window.location.reload()} />
          <div style={{ textAlign: 'center', marginTop: 16 }}>
            <a href="/" className="ad-link">{T.backHome}</a>
          </div>
        </div>
      </div>
    );
  }

  // ---------- Trang quản trị ----------
  const navItems = [
    ['overview', '📊'], ['online', '🟢', stats?.onlineNow], ['users', '👥', stats?.totalUsers],
    ['logs', '📜'], ['resets', '🔑', stats?.pendingResetRequests, true], ['settings', '⚙️'],
  ];
  const onChanged = () => loadStats();

  return (
    <div className="ad-root">
      <style>{adCss}</style>
      {menuOpen && <div className="ad-scrim" onClick={() => setMenuOpen(false)} />}
      <aside className={'ad-side' + (menuOpen ? ' open' : '')}>
        <div className="ad-brand">🛡️ {T.brand}</div>
        <nav>
          {navItems.map(([key, icon, count, alert]) => (
            <button key={key} className={'ad-nav' + (tab === key ? ' active' : '')} onClick={() => { setTab(key); setMenuOpen(false); }}>
              <span style={{ width: 22 }}>{icon}</span>
              <span style={{ flex: 1, textAlign: 'left' }}>{T.nav[key]}</span>
              {count > 0 && <span className={'ad-count' + (alert ? ' alert' : '')}>{count}</span>}
            </button>
          ))}
        </nav>
        <div className="ad-side-foot">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
            <Avatar user={me} size={34} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: 14, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{me.name}</div>
              <div style={{ fontSize: 12, color: '#64748b' }}>@{me.username}</div>
            </div>
          </div>
          <a href="/" className="ad-btn" style={{ width: '100%', justifyContent: 'center', boxSizing: 'border-box', marginBottom: 8 }}>{T.backToApp}</a>
          <button className="ad-btn ad-btn-danger-soft" style={{ width: '100%', justifyContent: 'center' }} onClick={logout}>🚪 {T.logout}</button>
        </div>
      </aside>

      <main className="ad-main">
        <header className="ad-top">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
            <button className="ad-icon-btn ad-burger" onClick={() => setMenuOpen(true)} aria-label="menu">☰</button>
            <h1>{T.nav[tab]}</h1>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button className="ad-btn" onClick={refresh}>⟳ {T.refresh}</button>
            <select className="ad-input" value={language} onChange={(e) => setLanguage(e.target.value)} style={{ width: 'auto', padding: '7px 8px' }}>
              <option value="vi">🇻🇳 Tiếng Việt</option>
              <option value="en">🇺🇸 English</option>
            </select>
          </div>
        </header>

        <div className="ad-content">
          {tab === 'overview' && <Overview T={T} language={language} stats={stats} />}
          {tab === 'online' && <OnlineUsers T={T} me={me} notify={notify} refreshKey={refreshKey} onChanged={onChanged} />}
          {tab === 'users' && <Users T={T} language={language} me={me} notify={notify} refreshKey={refreshKey} onChanged={onChanged} />}
          {tab === 'logs' && <Logs T={T} language={language} notify={notify} refreshKey={refreshKey} />}
          {tab === 'resets' && <ResetRequests T={T} language={language} notify={notify} refreshKey={refreshKey} onChanged={onChanged} />}
          {tab === 'settings' && <Settings T={T} notify={notify} />}
        </div>
      </main>

      {toast && <div key={toast.id} className={'ad-toast ' + toast.type}>{toast.type === 'error' ? '⚠️ ' : '✅ '}{toast.message}</div>}
    </div>
  );
}

const adCss = `
.ad-root { display: flex; min-height: 100vh; background: #f7f9fc; color: #0f172a; font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; }
.ad-center { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px 16px; box-sizing: border-box; font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; background: #f7f9fc; }
.ad-login-bg { background: radial-gradient(circle at 20% 10%, #e7efff 0, transparent 40%), radial-gradient(circle at 80% 90%, #f1e9ff 0, transparent 40%), #f7f9fc; }
.ad-spinner { width: 34px; height: 34px; border-radius: 50%; border: 3px solid #dbe4ff; border-top-color: #1f4fd8; animation: ad-spin .8s linear infinite; }
@keyframes ad-spin { to { transform: rotate(360deg); } }
.ad-warn { background: #fff7ed; color: #9a3412; border: 1px solid #fed7aa; border-radius: 12px; padding: 10px 14px; font-size: 14px; margin-bottom: 12px; }
.ad-link { color: #64748b; font-size: 14px; text-decoration: none; }
.ad-link:hover { color: #1f4fd8; }

.ad-side { width: 248px; flex-shrink: 0; background: #fff; border-right: 1px solid #e8ecf2; display: flex; flex-direction: column; padding: 18px 12px; box-sizing: border-box; position: sticky; top: 0; height: 100vh; }
.ad-brand { font-weight: 800; font-size: 17px; padding: 4px 10px 18px; white-space: nowrap; }
.ad-side nav { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.ad-nav { display: flex; align-items: center; gap: 8px; padding: 9px 10px; border-radius: 9px; border: none; background: none; color: #334155; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background .15s; }
.ad-nav:hover { background: #f4f6fa; }
.ad-nav.active { background: #eef3ff; color: #1f4fd8; font-weight: 600; }
.ad-count { background: #f1f5f9; color: #475569; font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 10px; }
.ad-count.alert { background: #ff8a00; color: #fff; }
.ad-side-foot { border-top: 1px solid #e8ecf2; padding-top: 14px; }
.ad-scrim { display: none; }

.ad-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.ad-top { position: sticky; top: 0; z-index: 5; display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 14px 28px; background: rgba(247,249,252,0.9); backdrop-filter: blur(10px); border-bottom: 1px solid #eef1f6; }
.ad-top h1 { margin: 0; font-size: 20px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ad-burger { display: none; }
.ad-content { padding: 22px 28px 40px; max-width: 1280px; width: 100%; box-sizing: border-box; }

.ad-card { background: #fff; border: 1px solid #e8ecf2; border-radius: 14px; padding: 18px; box-sizing: border-box; }
.ad-card-title { font-weight: 700; font-size: 15px; margin-bottom: 14px; }
.ad-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 14px; margin-bottom: 16px; }
.ad-stat { display: flex; align-items: center; gap: 14px; }
.ad-stat-icon { width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; }
.ad-stat-value { font-size: 24px; font-weight: 800; line-height: 1.1; }
.ad-stat-label { font-size: 13px; color: #64748b; margin-top: 2px; }
.ad-grid-2 { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 16px; }
.ad-split { display: flex; height: 12px; border-radius: 6px; overflow: hidden; background: #f1f5f9; }
.ad-dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; vertical-align: 0; }
.ad-pulse { box-shadow: 0 0 0 0 rgba(34,197,94,0.5); animation: ad-pulse 1.8s infinite; }
@keyframes ad-pulse { 0% { box-shadow: 0 0 0 0 rgba(34,197,94,0.5); } 70% { box-shadow: 0 0 0 7px rgba(34,197,94,0); } 100% { box-shadow: 0 0 0 0 rgba(34,197,94,0); } }
.ad-muted { color: #64748b; font-size: 14px; }
.ad-empty { text-align: center; padding: 40px 16px; color: #64748b; border: 1px dashed #d8dfea; border-radius: 12px; }

.ad-toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.ad-search { display: flex; align-items: center; gap: 8px; border: 1px solid #dbe1ea; border-radius: 10px; padding: 0 10px; background: #fff; flex: 1; min-width: 200px; max-width: 360px; font-size: 14px; }
.ad-search input { border: none; outline: none; padding: 9px 0; flex: 1; min-width: 0; font-size: 14px; background: none; }
.ad-input { width: 100%; box-sizing: border-box; padding: 9px 11px; border-radius: 10px; border: 1px solid #dbe1ea; font-size: 14px; outline: none; background: #fff; color: #0f172a; font-family: inherit; }
.ad-input:focus { border-color: #1f4fd8; box-shadow: 0 0 0 3px rgba(31,79,216,0.12); }
.ad-label { display: block; font-size: 13px; font-weight: 600; color: #334155; margin: 12px 0 6px; }

.ad-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 9px; border: 1px solid #e2e8f0; background: #fff; color: #0f172a; font-size: 14px; font-weight: 600; cursor: pointer; text-decoration: none; font-family: inherit; white-space: nowrap; transition: background .15s, border-color .15s; }
.ad-btn:hover { background: #f8fafc; border-color: #cbd5e1; }
.ad-btn:disabled { opacity: .65; cursor: not-allowed; }
.ad-btn-primary { background: #1f4fd8; border-color: #1f4fd8; color: #fff; }
.ad-btn-primary:hover { background: #1a43bb; border-color: #1a43bb; }
.ad-btn-danger-soft { color: #b91c1c; border-color: #fecaca; background: #fff; }
.ad-btn-danger-soft:hover { background: #fef2f2; border-color: #fca5a5; }
.ad-icon-btn { width: 32px; height: 32px; border-radius: 8px; border: 1px solid #e8ecf2; background: #fff; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; font-size: 14px; }
.ad-icon-btn:hover { background: #f4f6fa; }
.ad-icon-danger:hover { background: #fef2f2; border-color: #fecaca; }
.ad-actions { display: flex; gap: 6px; justify-content: flex-end; }

.ad-table-wrap { overflow-x: auto; border: 1px solid #e8ecf2; border-radius: 12px; }
.ad-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ad-table th { background: #fafbfd; color: #64748b; font-size: 11px; text-transform: uppercase; letter-spacing: .5px; text-align: left; padding: 10px 12px; border-bottom: 1px solid #e8ecf2; white-space: nowrap; font-weight: 700; }
.ad-table td { padding: 10px 12px; border-bottom: 1px solid #f1f4f8; vertical-align: middle; }
.ad-table tr:last-child td { border-bottom: none; }
.ad-table tbody tr:hover { background: #fafbfd; }
.ad-row-locked td { opacity: .65; }
.ad-nowrap { white-space: nowrap; }
.ad-sticky { position: sticky; right: 0; background: #fff; box-shadow: -8px 0 10px -8px rgba(15,23,42,0.12); }
.ad-table th.ad-sticky { background: #fafbfd; }
.ad-table tbody tr:hover .ad-sticky { background: #fafbfd; }

.ad-badge { display: inline-block; padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; background: #f1f5f9; color: #475569; white-space: nowrap; }
.ad-badge-blue { background: #eef3ff; color: #1f4fd8; }
.ad-badge-purple { background: #f3eefe; color: #6d28d9; }
.ad-badge-green { background: #ecfdf3; color: #15803d; }
.ad-badge-red { background: #fef2f2; color: #b91c1c; }
.ad-badge-orange { background: #fff4e5; color: #c2410c; }
.ad-badge-yellow { background: #fefce8; color: #a16207; }
.ad-badge-gray { background: #f1f5f9; color: #475569; }

.ad-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.45); backdrop-filter: blur(2px); z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; }
.ad-modal { background: #fff; border-radius: 16px; width: 100%; padding: 20px 22px; box-shadow: 0 24px 60px rgba(15,23,42,0.25); max-height: 90vh; overflow-y: auto; box-sizing: border-box; }
.ad-modal-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ad-modal-head h3 { margin: 0; font-size: 18px; }
.ad-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.ad-error { margin-top: 12px; background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-radius: 10px; padding: 9px 12px; font-size: 13px; }
.ad-note { font-size: 12.5px; color: #64748b; background: #f8fafc; border: 1px solid #eef1f6; border-radius: 10px; padding: 8px 10px; margin: 12px 0 0; line-height: 1.5; }
.ad-secret { display: flex; align-items: center; gap: 10px; background: #f7f9fc; border: 1px dashed #b9c8f5; border-radius: 12px; padding: 10px 10px 10px 14px; }
.ad-secret code { flex: 1; font-size: 18px; font-weight: 700; letter-spacing: 1px; color: #1f4fd8; word-break: break-all; }

.ad-toast { position: fixed; bottom: 22px; right: 22px; z-index: 70; background: #0f172a; color: #fff; padding: 11px 16px; border-radius: 12px; font-size: 14px; box-shadow: 0 12px 30px rgba(15,23,42,0.3); animation: ad-in .2s ease-out; max-width: calc(100vw - 44px); }
.ad-toast.error { background: #b91c1c; }
@keyframes ad-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }

@media (max-width: 1000px) { .ad-grid-2 { grid-template-columns: 1fr; } }
@media (max-width: 820px) {
  .ad-side { position: fixed; left: 0; top: 0; bottom: 0; z-index: 40; transform: translateX(-100%); transition: transform .2s ease; box-shadow: 8px 0 30px rgba(15,23,42,0.15); }
  .ad-side.open { transform: none; }
  .ad-scrim { display: block; position: fixed; inset: 0; background: rgba(15,23,42,0.35); z-index: 39; }
  .ad-burger { display: inline-flex; }
  .ad-top { padding: 12px 14px; }
  .ad-top h1 { font-size: 17px; }
  .ad-content { padding: 16px 14px 32px; }
  .ad-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .ad-stat { gap: 10px; }
  .ad-stat-icon { width: 36px; height: 36px; font-size: 17px; }
  .ad-stat-value { font-size: 20px; }
}
`;
