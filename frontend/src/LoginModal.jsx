import { useState } from 'react';
import axios from 'axios';
import { authText, authErrorMessage } from './authText';

// Bảng đăng nhập dùng chung cho trang chủ và trang quản trị.
// Các màn hình: login | register | forgot | admin

const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';
const api = axios.create({ baseURL: API_BASE_URL, withCredentials: true });

const GOOGLE_LOGO = 'https://upload.wikimedia.org/wikipedia/commons/c/c1/Google_%22G%22_logo.svg';

function PasswordInput({ value, onChange, placeholder, L, autoComplete, autoFocus }) {
  const [show, setShow] = useState(false);
  return (
    <div className="lm-pw">
      <input type={show ? 'text' : 'password'} value={value} onChange={onChange} placeholder={placeholder}
        autoComplete={autoComplete} autoFocus={autoFocus} className="lm-input" />
      <button type="button" className="lm-eye" onClick={() => setShow(s => !s)} title={show ? L.hidePw : L.showPw} aria-label={show ? L.hidePw : L.showPw}>
        {show ? '🙈' : '👁'}
      </button>
    </div>
  );
}

/**
 * props:
 *  - language, setLanguage (tùy chọn: hiện nút đổi ngôn ngữ)
 *  - initialView: 'login' | 'register' | 'forgot' | 'admin'
 *  - notice: dòng thông báo phía trên (VD: "Bạn cần đăng nhập...")
 *  - initialError: lỗi hiển thị sẵn (VD: đăng nhập Google thất bại)
 *  - onClose: không truyền = không cho đóng (dùng ở trang admin)
 *  - onLoggedIn(user, { admin }) : gọi sau khi đăng nhập / đăng ký thành công
 *  - adminOnly: chỉ hiện màn hình đăng nhập quản trị (trang /admin)
 */
export default function LoginModal({ language, setLanguage, initialView = 'login', notice, initialError, onClose, onLoggedIn, adminOnly, inline }) {
  const L = authText[language] || authText.vi;
  const [view, setView] = useState(adminOnly ? 'admin' : initialView);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError || '');
  const [done, setDone] = useState('');

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [confirm, setConfirm] = useState('');
  const [identifier, setIdentifier] = useState('');
  const [note, setNote] = useState('');

  const go = (v) => { setView(v); setError(''); setDone(''); setPassword(''); setConfirm(''); };

  const submitLogin = async (e, asAdmin) => {
    e.preventDefault();
    if (!username.trim() || !password) { setError(L.errors.missing); return; }
    setBusy(true); setError('');
    try {
      const res = await api.post('/api/auth/login', { username: username.trim(), password, admin: !!asAdmin });
      onLoggedIn?.(res.data, { admin: !!asAdmin });
    } catch (err) {
      setError(authErrorMessage(err, L));
    } finally {
      setBusy(false);
    }
  };

  const submitRegister = async (e) => {
    e.preventDefault();
    if (!username.trim() || !name.trim() || !email.trim() || !password) { setError(L.errors.missing); return; }
    if (password !== confirm) { setError(L.errors.mismatch); return; }
    setBusy(true); setError('');
    try {
      const res = await api.post('/api/auth/register', { username: username.trim(), name: name.trim(), email: email.trim(), password });
      onLoggedIn?.(res.data, { admin: false });
    } catch (err) {
      setError(authErrorMessage(err, L));
    } finally {
      setBusy(false);
    }
  };

  const submitForgot = async (e) => {
    e.preventDefault();
    if (!identifier.trim()) { setError(L.errors.missing); return; }
    setBusy(true); setError('');
    try {
      await api.post('/api/auth/forgot', { identifier: identifier.trim(), note: note.trim() || null });
      setDone(L.forgotDone);
    } catch (err) {
      setError(authErrorMessage(err, L));
    } finally {
      setBusy(false);
    }
  };

  const loginWithGoogle = () => { window.location.href = API_BASE_URL + '/oauth2/authorization/google'; };

  const header = (title, sub, icon) => (
    <div style={{ textAlign: 'center', marginBottom: '20px' }}>
      <div style={{ fontSize: '30px', marginBottom: '6px' }}>{icon}</div>
      <h3 style={{ margin: '0 0 4px 0', fontSize: '21px', color: '#0f172a' }}>{title}</h3>
      <div style={{ color: '#64748b', fontSize: '14px', lineHeight: 1.5 }}>{sub}</div>
    </div>
  );

  const card = (
    <div className={'lm-card' + (inline ? ' lm-inline' : '')} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
      <style>{lmCss}</style>
      <div className="lm-top">
        {setLanguage ? (
          <select value={language} onChange={(e) => setLanguage(e.target.value)} className="lm-lang" aria-label="Language">
            <option value="vi">🇻🇳 VI</option>
            <option value="en">🇺🇸 EN</option>
          </select>
        ) : <span />}
        {onClose && <button className="lm-close" onClick={onClose} aria-label={L.close}>✕</button>}
      </div>

      {notice && view === 'login' && <div className="lm-notice">🔒 {notice}</div>}

      {/* ---------------- ĐĂNG NHẬP ---------------- */}
      {view === 'login' && (
        <>
          {header(L.loginTitle, L.loginSub, '🧪')}
          <button type="button" className="lm-google" onClick={loginWithGoogle}>
            <img src={GOOGLE_LOGO} alt="" style={{ width: '18px', height: '18px' }} /> {L.google}
          </button>
          <div className="lm-divider"><span>{L.or}</span></div>
          <form onSubmit={(e) => submitLogin(e, false)}>
            <label className="lm-label">{L.username}</label>
            <input className="lm-input" value={username} onChange={(e) => setUsername(e.target.value)} placeholder={L.usernameHint} autoComplete="username" autoFocus />
            <label className="lm-label">{L.password}</label>
            <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••" L={L} autoComplete="current-password" />
            {error && <div className="lm-error">{error}</div>}
            <button type="submit" className="lm-submit" disabled={busy}>{busy ? L.loading : L.loginBtn}</button>
          </form>
          <div className="lm-bottom">
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', alignItems: 'flex-start' }}>
              <button type="button" className="lm-link" onClick={() => go('forgot')}>{L.forgot}</button>
              <button type="button" className="lm-link lm-link-strong" onClick={() => go('register')}>{L.register}</button>
            </div>
            <button type="button" className="lm-admin" onClick={() => go('admin')}>🛡️ {L.admin}</button>
          </div>
        </>
      )}

      {/* ---------------- ĐĂNG KÝ ---------------- */}
      {view === 'register' && (
        <>
          {header(L.registerTitle, L.registerSub, '✨')}
          <form onSubmit={submitRegister}>
            <label className="lm-label">{L.username}</label>
            <input className="lm-input" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="vd: khanh_qa" autoComplete="username" autoFocus />
            <label className="lm-label">{L.fullName}</label>
            <input className="lm-input" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" />
            <label className="lm-label">{L.email}</label>
            <input className="lm-input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
            <label className="lm-label">{L.password}</label>
            <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••" L={L} autoComplete="new-password" />
            <label className="lm-label">{L.confirmPassword}</label>
            <PasswordInput value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder="••••••" L={L} autoComplete="new-password" />
            {error && <div className="lm-error">{error}</div>}
            <button type="submit" className="lm-submit" disabled={busy}>{busy ? L.loading : L.registerBtn}</button>
          </form>
          <div style={{ textAlign: 'center', marginTop: '14px', fontSize: '14px', color: '#64748b' }}>
            {L.haveAccount} <button type="button" className="lm-link lm-link-strong" onClick={() => go('login')}>{L.loginBtn}</button>
          </div>
        </>
      )}

      {/* ---------------- QUÊN MẬT KHẨU ---------------- */}
      {view === 'forgot' && (
        <>
          {header(L.forgotTitle, L.forgotSub, '🔑')}
          {done ? (
            <div className="lm-success">✅ {done}</div>
          ) : (
            <form onSubmit={submitForgot}>
              <label className="lm-label">{L.identifier}</label>
              <input className="lm-input" value={identifier} onChange={(e) => setIdentifier(e.target.value)} autoFocus />
              <label className="lm-label">{L.note}</label>
              <textarea className="lm-input" rows={2} value={note} onChange={(e) => setNote(e.target.value)} placeholder={L.notePlaceholder} style={{ resize: 'vertical', fontFamily: 'inherit' }} />
              {error && <div className="lm-error">{error}</div>}
              <button type="submit" className="lm-submit" disabled={busy}>{busy ? L.loading : L.forgotBtn}</button>
            </form>
          )}
          <div style={{ textAlign: 'center', marginTop: '14px' }}>
            <button type="button" className="lm-link" onClick={() => go('login')}>{L.backToLogin}</button>
          </div>
        </>
      )}

      {/* ---------------- QUẢN TRỊ VIÊN ---------------- */}
      {view === 'admin' && (
        <>
          {header(L.adminTitle, L.adminSub, '🛡️')}
          <form onSubmit={(e) => submitLogin(e, true)}>
            <label className="lm-label">{L.username}</label>
            <input className="lm-input" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
            <label className="lm-label">{L.password}</label>
            <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••" L={L} autoComplete="current-password" />
            {error && <div className="lm-error">{error}</div>}
            <button type="submit" className="lm-submit lm-submit-dark" disabled={busy}>{busy ? L.loading : L.adminBtn}</button>
          </form>
          {!adminOnly && (
            <div style={{ textAlign: 'center', marginTop: '14px' }}>
              <button type="button" className="lm-link" onClick={() => go('login')}>{L.userLogin}</button>
            </div>
          )}
        </>
      )}
    </div>
  );

  if (inline) return card;
  return (
    <div className="lm-overlay" onClick={onClose}>
      {card}
    </div>
  );
}

const lmCss = `
.lm-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.5); backdrop-filter: blur(3px); z-index: 60; display: flex; align-items: center; justify-content: center; padding: 16px; animation: lm-fade .15s ease-out; }
.lm-card { position: relative; background: #fff; border-radius: 18px; width: 100%; max-width: 400px; padding: 18px 28px 24px; box-shadow: 0 24px 60px rgba(15,23,42,0.28); max-height: 92vh; overflow-y: auto; box-sizing: border-box; font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; color: #0f172a; text-align: left; }
.lm-inline { box-shadow: 0 20px 50px rgba(15,23,42,0.10); border: 1px solid #e8ecf2; }
.lm-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; min-height: 28px; }
.lm-lang { border: 1px solid #e8ecf2; background: #fff; border-radius: 8px; padding: 4px 6px; font-size: 12px; cursor: pointer; color: #334155; }
.lm-close { background: none; border: none; font-size: 17px; color: #94a3b8; cursor: pointer; padding: 4px; }
.lm-close:hover { color: #334155; }
.lm-notice { background: #fff7ed; color: #9a3412; border: 1px solid #fed7aa; border-radius: 10px; padding: 9px 12px; font-size: 13px; margin-bottom: 14px; }
.lm-google { width: 100%; display: flex; align-items: center; justify-content: center; gap: 10px; padding: 11px; border-radius: 10px; border: 1px solid #dadce0; background: #fff; color: #1f1f1f; font-weight: 600; font-size: 15px; cursor: pointer; transition: background .15s, box-shadow .15s; font-family: inherit; }
.lm-google:hover { background: #f8fafd; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
.lm-divider { display: flex; align-items: center; gap: 12px; color: #94a3b8; font-size: 12px; margin: 18px 0 8px; text-transform: uppercase; letter-spacing: .5px; }
.lm-divider::before, .lm-divider::after { content: ''; flex: 1; height: 1px; background: #e8ecf2; }
.lm-label { display: block; font-size: 13px; font-weight: 600; color: #334155; margin: 12px 0 6px; }
.lm-input { width: 100%; box-sizing: border-box; padding: 10px 12px; border-radius: 10px; border: 1px solid #dbe1ea; font-size: 15px; outline: none; transition: border-color .15s, box-shadow .15s; background: #fff; color: #0f172a; }
.lm-input:focus { border-color: #1f4fd8; box-shadow: 0 0 0 3px rgba(31,79,216,0.12); }
.lm-pw { position: relative; }
.lm-pw .lm-input { padding-right: 42px; }
.lm-eye { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); background: none; border: none; cursor: pointer; font-size: 16px; padding: 4px 6px; opacity: .7; }
.lm-eye:hover { opacity: 1; }
.lm-error { margin-top: 12px; background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-radius: 10px; padding: 9px 12px; font-size: 13px; }
.lm-success { background: #f0fdf4; color: #166534; border: 1px solid #bbf7d0; border-radius: 10px; padding: 12px 14px; font-size: 14px; line-height: 1.5; }
.lm-submit { width: 100%; margin-top: 16px; padding: 11px; border: none; border-radius: 10px; background: #1f4fd8; color: #fff; font-weight: 700; font-size: 15px; cursor: pointer; transition: background .15s; font-family: inherit; }
.lm-submit:hover:not(:disabled) { background: #1a43bb; }
.lm-submit:disabled { opacity: .7; cursor: not-allowed; }
.lm-submit-dark { background: #0f172a; }
.lm-submit-dark:hover:not(:disabled) { background: #1e293b; }
.lm-bottom { display: flex; justify-content: space-between; align-items: flex-end; margin-top: 18px; padding-top: 14px; border-top: 1px solid #f1f4f8; }
.lm-link { background: none; border: none; padding: 0; color: #64748b; font-size: 13px; cursor: pointer; font-family: inherit; }
.lm-link:hover { color: #1f4fd8; text-decoration: underline; }
.lm-link-strong { color: #1f4fd8; font-weight: 600; font-size: 14px; }
.lm-admin { background: #f1f5f9; color: #334155; border: 1px solid #e2e8f0; border-radius: 8px; padding: 7px 12px; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit; }
.lm-admin:hover { background: #e2e8f0; }
@keyframes lm-fade { from { opacity: 0; } to { opacity: 1; } }
@media (max-width: 420px) { .lm-card { padding: 16px 18px 20px; } }
`;
