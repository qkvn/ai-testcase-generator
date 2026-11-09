import { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import TraceabilityPanel from './TraceabilityPanel';
import LandingPage from './LandingPage';
import { computeCoverage, parseRequirementIds } from './traceability';

axios.defaults.withCredentials = true;

// Xác định môi trường: Trống nếu chạy trên Render, dùng localhost nếu chạy dưới máy.
const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';

// Thư viện xuất Excel/PDF khá nặng -> chỉ tải khi người dùng bấm xuất file
const loadExcelLibs = async () => {
  const [excelMod, saverMod] = await Promise.all([import('exceljs'), import('file-saver')]);
  const ExcelJS = excelMod.default || excelMod;
  const saveAs = saverMod.saveAs || saverMod.default?.saveAs || saverMod.default;
  return { ExcelJS, saveAs };
};
const loadPdfLibs = async () => {
  const [pdfMod, autoTableMod] = await Promise.all([import('jspdf'), import('jspdf-autotable')]);
  return { jsPDF: pdfMod.jsPDF || pdfMod.default, autoTable: autoTableMod.default || autoTableMod.autoTable };
};

const translations = {
  vi: {
    title: "Hệ Thống Tự Động Sinh Test Case",
    subtitle: "Đồ án tốt nghiệp - Spring Boot & Groq AI",
    inputLabel: "Nhập User Story / Requirement:",
    placeholder: "Nhập yêu cầu chức năng (VD: Chức năng đăng nhập...)",
    btnGenerate: "Sinh Test Case",
    btnLoading: "AI đang phân tích...",
    btnExport: "📥 Xuất Excel",
    btnSave: "💾 Lưu thay đổi",
    listTitle: "Danh Sách Test Cases",
    emptyList: "Chưa có test case nào cho phiên này.",
    colId: "Mã TC",
    colSummary: "Tóm tắt (Summary)",
    colPrecondition: "Điều kiện tiên quyết",
    colSteps: "Các bước (Steps)",
    colExpected: "Kết quả mong đợi",
    errEmpty: "Vui lòng nhập nội dung yêu cầu.",
    errConnect: "Không thể kết nối Backend: ",
    newChat: "Cuộc trò chuyện mới",
    search: "Tìm kiếm lịch sử...",
    history: "Lịch sử gần đây",
    checkingAuth: "Đang kiểm tra thông tin đăng nhập...",
    loginTitle: "Đăng Nhập Hệ Thống",
    loginBtn: "Đăng nhập bằng Google",
    logout: "Đăng xuất",
    exitShare: "← Thoát chế độ xem chung",
    saveSuccess: "Đã lưu các thay đổi thành công!",
    colReqs: "Yêu cầu",
    tabTestCases: "📋 Test Cases",
    tabTrace: "🔗 Truy vết & Độ bao phủ",
    traceAnalyze: "AI phân tích truy vết",
    traceAnalyzing: "AI đang phân tích...",
    traceAnalyzeConfirm: "AI sẽ phân tích lại danh sách yêu cầu và thay thế toàn bộ liên kết hiện tại. Tiếp tục?",
    traceUnsavedFirst: "Bạn có thay đổi chưa lưu. Hãy bấm \"Lưu thay đổi\" trước khi phân tích.",
    traceNoSession: "Không xác định được phiên hiện tại.",
    traceEmpty: "Phiên này chưa có danh sách yêu cầu. Hãy để AI phân tích User Story và test case để tạo ma trận truy vết.",
    traceAddReq: "Thêm yêu cầu",
    traceAddBtn: "Thêm",
    traceNewReqPlaceholder: "Mô tả yêu cầu mới...",
    traceCoverage: "Độ bao phủ yêu cầu",
    traceReqCovered: "yêu cầu đã được kiểm thử",
    traceUncovered: "Yêu cầu chưa bao phủ",
    traceOrphans: "Test case chưa gắn yêu cầu",
    traceUncoveredWarn: "Các yêu cầu chưa có test case:",
    traceOrphanWarn: "Test case chưa truy vết tới yêu cầu nào:",
    traceReqList: "Danh sách yêu cầu",
    traceColCode: "Mã YC",
    traceColDesc: "Mô tả yêu cầu",
    traceColPriority: "Ưu tiên",
    traceColLinked: "Test case liên kết",
    traceColStatus: "Trạng thái",
    traceCovered: "Đã bao phủ",
    traceNotCovered: "Chưa bao phủ",
    traceDelete: "Xóa yêu cầu",
    traceDeleteConfirm: "Xóa yêu cầu này và gỡ liên kết khỏi các test case?",
    traceMatrix: "Ma trận truy vết (RTM)",
    traceMatrixHint: "Nhấp vào ô để gắn / bỏ liên kết giữa yêu cầu và test case, sau đó bấm \"Lưu thay đổi\".",
    sheetReqs: "Yêu cầu",
    sheetMatrix: "Ma trận truy vết",
    sharedTitle: "Bộ test case được chia sẻ",
    shareTitle: "Chia sẻ bộ test case",
    shareCopy: "Sao chép",
    shareNote: "Bất kỳ ai có đường liên kết đều có thể xem bộ test case này sau khi đăng nhập. Hãy chia sẻ một cách có trách nhiệm.",
    noHistory: "Chưa có lịch sử nào.",
    menuShare: "Chia sẻ",
    menuPin: "Ghim",
    menuUnpin: "Bỏ ghim",
    menuRename: "Đổi tên",
    menuPdf: "Tải bản PDF xuống",
    menuExcel: "Xuất sang Excel",
    menuDelete: "Xóa",
    heroHello: "Xin chào",
    heroSub: "Hôm nay bạn muốn kiểm thử chức năng nào? Mô tả User Story, AI sẽ sinh test case và ma trận truy vết cho bạn.",
    suggestions: [
      { label: "🔐 Đăng nhập", text: "Là người dùng, tôi muốn đăng nhập bằng email và mật khẩu để truy cập hệ thống. Mật khẩu tối thiểu 8 ký tự, sai 5 lần sẽ bị khóa 15 phút." },
      { label: "🛒 Giỏ hàng", text: "Là khách hàng, tôi muốn thêm sản phẩm vào giỏ hàng, thay đổi số lượng và áp dụng mã giảm giá trước khi thanh toán." },
      { label: "🔑 Quên mật khẩu", text: "Là người dùng, tôi muốn đặt lại mật khẩu qua email khi quên mật khẩu. Link đặt lại có hiệu lực trong 30 phút." },
      { label: "📝 Đăng ký", text: "Là khách truy cập, tôi muốn đăng ký tài khoản với họ tên, email, số điện thoại và mật khẩu. Email không được trùng." }
    ],
    editHint: "Click đúp vào ô để sửa",
    generatingTitle: "AI đang phân tích User Story...",
    generatingSub: "Quá trình này thường mất khoảng 5–15 giây.",
    emptyHint: "Nhập User Story ở trên hoặc chọn một gợi ý để bắt đầu."
  },
  en: {
    title: "Automated Test Case Generator",
    subtitle: "Graduation Project - Spring Boot & Groq AI",
    inputLabel: "Enter User Story / Requirement:",
    placeholder: "Enter feature requirements...",
    btnGenerate: "Generate",
    btnLoading: "AI Analyzing...",
    btnExport: "📥 Export Excel",
    btnSave: "💾 Save Changes",
    listTitle: "Test Cases List",
    emptyList: "No test cases for this session yet.",
    colId: "TC ID",
    colSummary: "Summary",
    colPrecondition: "Precondition",
    colSteps: "Steps to Reproduce",
    colExpected: "Expected Result",
    errEmpty: "Please enter requirement.",
    errConnect: "Cannot connect to Backend: ",
    newChat: "New Chat",
    search: "Search history...",
    history: "Recent History",
    checkingAuth: "Checking authentication...",
    loginTitle: "System Login",
    loginBtn: "Login with Google",
    logout: "Logout",
    exitShare: "← Exit Shared View",
    saveSuccess: "Changes saved successfully!",
    colReqs: "Requirements",
    tabTestCases: "📋 Test Cases",
    tabTrace: "🔗 Traceability & Coverage",
    traceAnalyze: "AI traceability analysis",
    traceAnalyzing: "AI analyzing...",
    traceAnalyzeConfirm: "AI will re-analyze the requirements and replace all current links. Continue?",
    traceUnsavedFirst: "You have unsaved changes. Please click \"Save Changes\" before analyzing.",
    traceNoSession: "Cannot determine the current session.",
    traceEmpty: "This session has no requirements yet. Let AI analyze the user story and test cases to build the traceability matrix.",
    traceAddReq: "Add requirement",
    traceAddBtn: "Add",
    traceNewReqPlaceholder: "New requirement description...",
    traceCoverage: "Requirement coverage",
    traceReqCovered: "requirements tested",
    traceUncovered: "Uncovered requirements",
    traceOrphans: "Unlinked test cases",
    traceUncoveredWarn: "Requirements without test cases:",
    traceOrphanWarn: "Test cases not traced to any requirement:",
    traceReqList: "Requirements",
    traceColCode: "Req ID",
    traceColDesc: "Description",
    traceColPriority: "Priority",
    traceColLinked: "Linked test cases",
    traceColStatus: "Status",
    traceCovered: "Covered",
    traceNotCovered: "Not covered",
    traceDelete: "Delete requirement",
    traceDeleteConfirm: "Delete this requirement and unlink it from all test cases?",
    traceMatrix: "Requirement Traceability Matrix (RTM)",
    traceMatrixHint: "Click a cell to link / unlink a requirement and a test case, then click \"Save Changes\".",
    sheetReqs: "Requirements",
    sheetMatrix: "Traceability Matrix",
    sharedTitle: "Shared test suite",
    shareTitle: "Share test suite",
    shareCopy: "Copy",
    shareNote: "Anyone with the link can view this test suite after signing in. Please share responsibly.",
    noHistory: "No history yet.",
    menuShare: "Share",
    menuPin: "Pin",
    menuUnpin: "Unpin",
    menuRename: "Rename",
    menuPdf: "Download PDF",
    menuExcel: "Export to Excel",
    menuDelete: "Delete",
    heroHello: "Hello",
    heroSub: "Which feature do you want to test today? Describe a user story and AI will generate test cases and a traceability matrix.",
    suggestions: [
      { label: "🔐 Sign in", text: "As a user, I want to sign in with email and password to access the system. Password must be at least 8 characters; 5 wrong attempts lock the account for 15 minutes." },
      { label: "🛒 Shopping cart", text: "As a customer, I want to add products to my cart, change quantities and apply a discount code before checkout." },
      { label: "🔑 Forgot password", text: "As a user, I want to reset my password via email when I forget it. The reset link is valid for 30 minutes." },
      { label: "📝 Sign up", text: "As a visitor, I want to register an account with full name, email, phone number and password. Email must be unique." }
    ],
    editHint: "Double-click a cell to edit",
    generatingTitle: "AI is analyzing the user story...",
    generatingSub: "This usually takes about 5–15 seconds.",
    emptyHint: "Enter a user story above or pick a suggestion to get started."
  }
};

function App() {
  const [userStory, setUserStory] = useState('');
  const [testCases, setTestCases] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [language, setLanguage] = useState('vi');

  const [showPlusMenu, setShowPlusMenu] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(() => typeof window === 'undefined' || window.innerWidth > 768);
  const [searchQuery, setSearchQuery] = useState('');

  const [chatSessions, setChatSessions] = useState([]);
  const [activeSessionId, setActiveSessionId] = useState(null);

  const [activeMenuId, setActiveMenuId] = useState(null);
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editTitle, setEditTitle] = useState('');

  const [shareModalSession, setShareModalSession] = useState(null);
  const [isSharedView, setIsSharedView] = useState(false);

  const [editingCell, setEditingCell] = useState({ id: null, field: null });
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const [currentUser, setCurrentUser] = useState(null);
  const [isAuthChecking, setIsAuthChecking] = useState(true);
  const [showSettingsMenu, setShowSettingsMenu] = useState(false);

  // Truy vết yêu cầu
  const [requirements, setRequirements] = useState([]);
  const [currentSessionId, setCurrentSessionId] = useState(null); // phiên đang hiển thị (kể cả phiên vừa sinh)
  const [activeTab, setActiveTab] = useState('testcases');
  const [traceBusy, setTraceBusy] = useState(false);

  // Cache dữ liệu từng phiên: mở lại phiên cũ hiển thị ngay, dữ liệu mới được tải ngầm
  const sessionCache = useRef(new Map());
  const latestRequestedSession = useRef(null);

  const t = translations[language];

  useEffect(() => {
    const checkAuth = async () => {
      try {
        const res = await axios.get(API_BASE_URL + '/api/user/me');
        setCurrentUser(res.data);
        // Điền lại User Story người dùng đã nhập ở trang chủ trước khi đăng nhập
        try {
          const draft = sessionStorage.getItem('pendingUserStory');
          if (draft) { setUserStory(draft); sessionStorage.removeItem('pendingUserStory'); }
        } catch { /* sessionStorage không khả dụng */ }
      } catch (err) {
        setCurrentUser(null);
      } finally {
        setIsAuthChecking(false);
      }
    };
    checkAuth();
  }, []);

  const handleLogin = () => { window.location.href = API_BASE_URL + '/oauth2/authorization/google'; };
  const handleLogout = () => { window.location.href = API_BASE_URL + '/api/logout'; };

  const filteredHistory = chatSessions.filter(item =>
    item.title && item.title.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const fetchSessions = async () => {
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions');
      setChatSessions(res.data);
    } catch (err) {
      console.error('Lỗi khi tải lịch sử:', err);
    }
  };

  useEffect(() => {
    if (currentUser) {
      const params = new URLSearchParams(window.location.search);
      const sharedId = params.get('share');

      if (sharedId) {
        setIsSharedView(true);
        setIsSidebarOpen(false);
        fetchTestCasesOnly(Number(sharedId));
      } else {
        setIsSharedView(false);
        fetchSessions();
      }
    }
  }, [currentUser]);

  const handleNewChat = () => {
    setActiveSessionId(null);
    setCurrentSessionId(null);
    setTestCases([]);
    setRequirements([]);
    setActiveTab('testcases');
    setUserStory('');
    setError('');
    setHasUnsavedChanges(false);
    window.history.pushState({}, '', window.location.pathname);
  };

  const handleExitSharedView = () => {
    setIsSharedView(false);
    setIsSidebarOpen(true);
    window.history.pushState({}, '', window.location.pathname);
    handleNewChat();
    fetchSessions();
  };

  const fetchTestCasesOnly = async (sessionId) => {
    latestRequestedSession.current = sessionId;
    setActiveSessionId(sessionId);
    setCurrentSessionId(sessionId);
    setError('');
    setHasUnsavedChanges(false);

    const cached = sessionCache.current.get(sessionId);
    if (cached) {
      setTestCases(cached.testCases);
      setRequirements(cached.requirements);
    } else {
      setTestCases([]);
      setRequirements([]);
      setLoading(true);
    }

    try {
      const [tcRes, reqRes] = await Promise.all([
        axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/testcases'),
        axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/requirements').catch(() => ({ data: [] }))
      ]);
      const fresh = { testCases: tcRes.data, requirements: reqRes.data || [] };
      sessionCache.current.set(sessionId, fresh);
      // Người dùng đã chuyển sang phiên khác trong lúc chờ -> bỏ kết quả cũ
      if (latestRequestedSession.current !== sessionId) return;
      setTestCases(fresh.testCases);
      setRequirements(fresh.requirements);
    } catch (err) {
      if (latestRequestedSession.current === sessionId && !cached) {
        setError("Lỗi tải test case: " + (err.response?.data?.message || err.message));
      }
    } finally {
      if (latestRequestedSession.current === sessionId) setLoading(false);
    }
  };

  // Luôn đồng bộ cache với dữ liệu đang hiển thị của phiên hiện tại
  useEffect(() => {
    if (currentSessionId) sessionCache.current.set(currentSessionId, { testCases, requirements });
  }, [currentSessionId, testCases, requirements]);

  const handleSelectSession = (sessionId) => {
    fetchTestCasesOnly(sessionId);
  };

  const handleFakeUpload = () => {
    setShowPlusMenu(false);
    alert("Tính năng phân tích hình ảnh đang được phát triển. Sẽ ra mắt trong các phiên bản cập nhật tiếp theo!");
  };

  const handleGenerate = async () => {
    if (!userStory.trim()) {
      setError(t.errEmpty);
      return;
    }
    setError(''); setLoading(true);
    try {
      const res = await axios.post(API_BASE_URL + '/api/generate?lang=' + language, userStory, {
        headers: { 'Content-Type': 'text/plain' }
      });
      setTestCases(res.data.testCases || []);
      setRequirements(res.data.requirements || []);
      setCurrentSessionId(res.data.sessionId || null);
      latestRequestedSession.current = res.data.sessionId || null;
      setUserStory('');
      setHasUnsavedChanges(false);
      fetchSessions(); // tải lại lịch sử ngầm, không bắt người dùng chờ
      setActiveSessionId(null);
    } catch (err) {
      setError(t.errConnect + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
    }
  };

  const handleStartRename = (session) => {
    setEditingSessionId(session.id);
    setEditTitle(session.title);
    setActiveMenuId(null);
  };

  const isPinned = (s) => s.pinned === true || s.isPinned === true;
  const sortSessions = (list) => [...list].sort((a, b) => (isPinned(b) - isPinned(a)) || (b.id - a.id));

  const handleSaveRename = async (sessionId) => {
    const newTitle = editTitle.trim();
    setEditingSessionId(null);
    const old = chatSessions.find(s => s.id === sessionId);
    if (!newTitle || !old || old.title === newTitle) return;
    setChatSessions(prev => prev.map(s => s.id === sessionId ? { ...s, title: newTitle } : s));
    try {
      await axios.put(API_BASE_URL + '/api/sessions/' + sessionId + '/rename', newTitle, { headers: { 'Content-Type': 'text/plain' } });
    } catch (err) {
      setChatSessions(prev => prev.map(s => s.id === sessionId ? { ...s, title: old.title } : s));
      alert("Lỗi khi đổi tên: " + (err.response?.data?.message || err.message));
    }
  };

  const handleDeleteSession = async (sessionId) => {
    if (!window.confirm("Bạn có chắc chắn muốn xóa lịch sử này không?")) return;
    setActiveMenuId(null);
    const before = chatSessions;
    setChatSessions(prev => prev.filter(s => s.id !== sessionId));
    sessionCache.current.delete(sessionId);
    if (activeSessionId === sessionId || currentSessionId === sessionId) handleNewChat();
    try {
      await axios.delete(API_BASE_URL + '/api/sessions/' + sessionId);
    } catch (err) {
      setChatSessions(before);
      alert("Lỗi khi xóa: " + (err.response?.data?.message || err.message));
    }
  };

  const handleTogglePin = async (session) => {
    setActiveMenuId(null);
    const toggle = (list) => sortSessions(list.map(s => s.id === session.id ? { ...s, pinned: !isPinned(s), isPinned: !isPinned(s) } : s));
    setChatSessions(prev => toggle(prev));
    try {
      await axios.put(API_BASE_URL + '/api/sessions/' + session.id + '/pin');
    } catch (err) {
      setChatSessions(prev => toggle(prev));
      alert("Lỗi khi ghim: " + (err.response?.data?.message || err.message));
    }
  };

  const handleCopyLink = () => {
    const shareUrl = window.location.origin + '/?share=' + shareModalSession.id;
    navigator.clipboard.writeText(shareUrl).then(() => {
      alert(language === 'en' ? 'Link copied to clipboard!' : 'Đã sao chép đường liên kết vào khay nhớ tạm!');
    });
  };

  const handleDoubleClickCell = (id, field) => {
    if (isSharedView) return; 
    setEditingCell({ id, field });
  };

  const handleChangeCell = (id, field, value) => {
    setTestCases(prev => prev.map(tc => {
      if (tc.id === id) {
        let newValue = value;
        if (field === 'steps') newValue = value.split('\n').filter(s => s.trim() !== '');
        if (field === 'requirementIds') newValue = parseRequirementIds(value);
        return { ...tc, [field]: newValue };
      }
      return tc;
    }));
    setHasUnsavedChanges(true);
  };

  const handleSaveChangesDB = async () => {
      setLoading(true);
      try {
        await axios.put(API_BASE_URL + '/api/testcases', testCases);
        setHasUnsavedChanges(false);
        alert(t.saveSuccess);
      } catch (err) {
        alert("Lỗi khi lưu dữ liệu: " + (err.response?.data?.message || err.message));
      } finally {
        setLoading(false);
      }
    };

  const renderEditableCell = (tc, field) => {
    const isEditing = editingCell.id === tc.id && editingCell.field === field;
    if (isEditing) {
      const val = field === 'steps' ? (tc[field] ? tc[field].join('\n') : '')
        : field === 'requirementIds' ? (tc[field] || []).join(', ') : tc[field];
      return (
        <textarea
          autoFocus
          defaultValue={val}
          onBlur={(e) => {
            handleChangeCell(tc.id, field, e.target.value);
            setEditingCell({ id: null, field: null });
          }}
          style={{ width: '100%', minHeight: '80px', padding: '8px', boxSizing: 'border-box', border: '2px solid #1f4fd8', borderRadius: '8px', outline: 'none', fontSize: '14px', fontFamily: 'inherit', boxShadow: '0 0 0 4px rgba(31,79,216,0.12)' }}
        />
      );
    }

    if (field === 'requirementIds') {
      const ids = tc.requirementIds || [];
      return (
        <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} title={isSharedView ? '' : 'REQ-01, REQ-02'} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px' }}>
          {ids.length ? ids.map(id => (
            <span key={id} style={{ display: 'inline-block', padding: '2px 8px', margin: '0 4px 4px 0', borderRadius: '10px', fontSize: '12px', fontWeight: 600, backgroundColor: '#e7f1ff', color: '#084298' }}>{id}</span>
          )) : <span style={{ color: '#adb5bd' }}>—</span>}
        </div>
      );
    }

    if (field === 'steps') {
      return (
        <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px' }}>
          <ol style={{ paddingLeft: '16px', margin: 0 }}>{tc.steps && tc.steps.map((step, idx) => (<li key={idx} style={{ marginBottom: '4px' }}>{step}</li>))}</ol>
        </div>
      );
    }

    return (
      <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px', whiteSpace: 'pre-wrap' }}>
        {tc[field]}
      </div>
    );
  };

  // ================== TRUY VẾT YÊU CẦU ==================
  const handleToggleLink = (tc, code) => {
    setTestCases(prev => prev.map(item => {
      if (item !== tc) return item;
      const ids = item.requirementIds || [];
      const next = ids.includes(code) ? ids.filter(c => c !== code) : [...ids, code];
      return { ...item, requirementIds: next };
    }));
    setHasUnsavedChanges(true);
  };

  const errMsg = (err) => {
    const d = err.response?.data;
    return (d && (d.message || (typeof d === 'string' ? d : ''))) || err.message;
  };

  const handleAnalyzeTraceability = async () => {
    if (!currentSessionId) { alert(t.traceNoSession); return; }
    if (hasUnsavedChanges) { alert(t.traceUnsavedFirst); return; }
    if (requirements.length > 0 && !window.confirm(t.traceAnalyzeConfirm)) return;
    setTraceBusy(true);
    try {
      await axios.post(API_BASE_URL + '/api/sessions/' + currentSessionId + '/traceability/analyze?lang=' + language);
      const [tcRes, reqRes] = await Promise.all([
        axios.get(API_BASE_URL + '/api/sessions/' + currentSessionId + '/testcases'),
        axios.get(API_BASE_URL + '/api/sessions/' + currentSessionId + '/requirements')
      ]);
      setTestCases(tcRes.data);
      setRequirements(reqRes.data);
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    } finally {
      setTraceBusy(false);
    }
  };

  const handleAddRequirement = async (data) => {
    if (!currentSessionId) { alert(t.traceNoSession); return; }
    try {
      const res = await axios.post(API_BASE_URL + '/api/sessions/' + currentSessionId + '/requirements', data);
      setRequirements(prev => [...prev, res.data]);
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    }
  };

  const handleUpdateRequirement = async (req, changes) => {
    setRequirements(prev => prev.map(r => r.dbId === req.dbId ? { ...r, ...changes } : r));
    try {
      await axios.put(API_BASE_URL + '/api/requirements/' + req.dbId, { ...req, ...changes });
    } catch (err) {
      setRequirements(prev => prev.map(r => r.dbId === req.dbId ? req : r));
      alert(t.errConnect + errMsg(err));
    }
  };

  const handleDeleteRequirement = async (req) => {
    if (!window.confirm(t.traceDeleteConfirm)) return;
    try {
      await axios.delete(API_BASE_URL + '/api/requirements/' + req.dbId);
      setRequirements(prev => prev.filter(r => r.dbId !== req.dbId));
      setTestCases(prev => prev.map(tc => ({ ...tc, requirementIds: (tc.requirementIds || []).filter(c => c !== req.code) })));
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    }
  };

  const styleHeaderRow = (row) => {
    row.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 12 };
    row.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF0D6EFD' } };
    row.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    row.height = 30;
  };

  const addBorders = (worksheet) => {
    worksheet.eachRow((row) => {
      row.eachCell((cell) => {
        cell.border = { top: { style: 'thin' }, left: { style: 'thin' }, bottom: { style: 'thin' }, right: { style: 'thin' } };
      });
    });
  };

  const generateFormattedExcel = async (dataToExport, fileNameSuffix, reqs = []) => {
    if (!dataToExport || dataToExport.length === 0) { alert("Không có dữ liệu!"); return; }
    const { ExcelJS, saveAs } = await loadExcelLibs();
    const workbook = new ExcelJS.Workbook();
    const worksheet = workbook.addWorksheet('Test Cases');
    worksheet.columns = [
      { header: t.colId, key: 'id', width: 12 }, { header: t.colSummary, key: 'summary', width: 40 },
      { header: t.colPrecondition, key: 'precondition', width: 35 }, { header: t.colSteps, key: 'steps', width: 55 },
      { header: t.colExpected, key: 'expected', width: 45 },
      { header: t.colReqs, key: 'reqs', width: 18 }
    ];
    const headerRow = worksheet.getRow(1);
    headerRow.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 12 };
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF0D6EFD' } };
    headerRow.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    headerRow.height = 30;

    dataToExport.forEach(tc => {
      const stepsText = tc.steps ? tc.steps.map((step, idx) => `${idx + 1}. ${step}`).join('\n') : '';
      worksheet.addRow({ id: tc.id, summary: tc.summary, precondition: tc.precondition, steps: stepsText, expected: tc.expected_result, reqs: (tc.requirementIds || []).join(', ') });
    });

    worksheet.eachRow((row, rowNumber) => {
      row.eachCell((cell) => {
        cell.border = { top: { style: 'thin' }, left: { style: 'thin' }, bottom: { style: 'thin' }, right: { style: 'thin' } };
        if (rowNumber > 1) cell.alignment = { vertical: 'top', wrapText: true };
      });
    });

    // Sheet Yêu cầu + Ma trận truy vết (nếu phiên có danh sách yêu cầu)
    if (reqs && reqs.length > 0) {
      const cov = computeCoverage(reqs, dataToExport);

      const reqSheet = workbook.addWorksheet(t.sheetReqs);
      reqSheet.columns = [
        { header: t.traceColCode, key: 'code', width: 12 }, { header: t.traceColDesc, key: 'desc', width: 60 },
        { header: t.traceColPriority, key: 'priority', width: 12 }, { header: t.traceColLinked, key: 'linked', width: 30 },
        { header: t.traceColStatus, key: 'status', width: 16 }
      ];
      styleHeaderRow(reqSheet.getRow(1));
      reqs.forEach(r => {
        const linked = cov.byReq[r.code] || [];
        const row = reqSheet.addRow({ code: r.code, desc: r.description, priority: r.priority, linked: linked.join(', '), status: linked.length ? t.traceCovered : t.traceNotCovered });
        row.alignment = { vertical: 'top', wrapText: true };
        if (!linked.length) row.getCell('status').font = { bold: true, color: { argb: 'FFDC3545' } };
      });
      addBorders(reqSheet);
      reqSheet.addRow({});
      const sumRow = reqSheet.addRow({ code: t.traceCoverage, desc: `${cov.percent}% (${cov.covered}/${cov.total})` });
      sumRow.font = { bold: true };

      const matrix = workbook.addWorksheet(t.sheetMatrix);
      matrix.columns = [
        { header: t.traceColCode + ' / TC', key: 'code', width: 16 },
        ...dataToExport.map((tc, i) => ({ header: tc.id, key: 'tc' + i, width: 10 })),
        { header: 'Σ', key: 'total', width: 8 }
      ];
      styleHeaderRow(matrix.getRow(1));
      reqs.forEach(r => {
        const rowData = { code: r.code, total: (cov.byReq[r.code] || []).length };
        dataToExport.forEach((tc, i) => { rowData['tc' + i] = (tc.requirementIds || []).includes(r.code) ? '✔' : ''; });
        const row = matrix.addRow(rowData);
        row.eachCell({ includeEmpty: true }, (cell, col) => {
          cell.alignment = { vertical: 'middle', horizontal: col === 1 ? 'left' : 'center' };
          if (cell.value === '✔') cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD1E7DD' } };
        });
        if (!rowData.total) row.getCell('code').font = { bold: true, color: { argb: 'FFDC3545' } };
      });
      matrix.views = [{ state: 'frozen', xSplit: 1, ySplit: 1 }];
      addBorders(matrix);
    }

    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    saveAs(blob, `TestCases_${fileNameSuffix}.xlsx`);
  };

  const handleMainExportExcel = () => generateFormattedExcel(testCases, "HienTai", requirements);

  const fetchSessionRequirements = async (sessionId) => {
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/requirements');
      return res.data || [];
    } catch { return []; }
  };

  const handleSidebarExportExcel = async (session) => {
    setActiveMenuId(null);
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + session.id + '/testcases');
      let safeTitle = session.title.replace(/[\/\\?%*:|"<>]/g, '-').substring(0, 30);
      const reqs = await fetchSessionRequirements(session.id);
      generateFormattedExcel(res.data, safeTitle, reqs);
    } catch (err) { alert("Lỗi tải dữ liệu: " + (err.response?.data?.message || err.message)); }
  };

  const handleSidebarExportPDF = async (session) => {
    setActiveMenuId(null);
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + session.id + '/testcases');
      const data = res.data;
      if (!data || data.length === 0) { alert("Không có dữ liệu để xuất PDF!"); return; }
      const { jsPDF, autoTable } = await loadPdfLibs();
      const doc = new jsPDF({ orientation: "landscape" });

      const loadFont = async (url, filename, fontName, fontStyle) => {
        try {
          const fontRes = await fetch(url);
          if (fontRes.ok) {
            const fontBuffer = await fontRes.arrayBuffer();
            const fontBytes = new Uint8Array(fontBuffer);
            let binary = '';
            for (let i = 0; i < fontBytes.byteLength; i++) { binary += String.fromCharCode(fontBytes[i]); }
            const base64Font = window.btoa(binary);
            doc.addFileToVFS(filename, base64Font);
            doc.addFont(filename, fontName, fontStyle);
          }
        } catch (fontErr) { console.error(`Lỗi tải font: `, fontErr); }
      };

      await loadFont('/arial.ttf', 'arial.ttf', 'Arial', 'normal');
      await loadFont('/arialbd.ttf', 'arialbd.ttf', 'Arial', 'bold');
      doc.setFont('Arial', 'normal');

      const tableColumn = [ t.colId, t.colSummary, t.colPrecondition, t.colSteps, t.colExpected, t.colReqs ];
      const tableRows = [];
      data.forEach(tc => {
        const stepsText = tc.steps ? tc.steps.map((step, idx) => `${idx + 1}. ${step}`).join('\n') : '';
        tableRows.push([ tc.id, tc.summary, tc.precondition, stepsText, tc.expected_result, (tc.requirementIds || []).join(', ') ]);
      });

      doc.setFont('Arial', 'bold');
      doc.text(`Danh sách Test Cases: ${session.title}`, 14, 15);
      autoTable(doc, {
        head: [tableColumn], body: tableRows, startY: 20,
        styles: { font: 'Arial', fontSize: 10, cellPadding: 3, overflow: 'linebreak' },
        headStyles: { fillColor: [13, 110, 253] },
        columnStyles: { 0: { cellWidth: 18 }, 1: { cellWidth: 50 }, 2: { cellWidth: 42 }, 3: { cellWidth: 74 }, 4: { cellWidth: 52 }, 5: { cellWidth: 32 } }
      });

      // Trang báo cáo truy vết & độ bao phủ
      const reqs = await fetchSessionRequirements(session.id);
      if (reqs.length > 0) {
        const cov = computeCoverage(reqs, data);
        doc.addPage();
        doc.setFont('Arial', 'bold');
        doc.text(`${t.traceMatrix} - ${t.traceCoverage}: ${cov.percent}% (${cov.covered}/${cov.total})`, 14, 15);
        autoTable(doc, {
          head: [[t.traceColCode, t.traceColDesc, t.traceColPriority, t.traceColLinked, t.traceColStatus]],
          body: reqs.map(r => {
            const linked = cov.byReq[r.code] || [];
            return [r.code, r.description, r.priority, linked.join(', '), linked.length ? t.traceCovered : t.traceNotCovered];
          }),
          startY: 20,
          styles: { font: 'Arial', fontSize: 10, cellPadding: 3, overflow: 'linebreak' },
          headStyles: { fillColor: [111, 66, 193] },
          columnStyles: { 0: { cellWidth: 22 }, 1: { cellWidth: 120 }, 2: { cellWidth: 22 }, 3: { cellWidth: 70 }, 4: { cellWidth: 34 } },
          didParseCell: (hook) => {
            if (hook.section === 'body' && hook.column.index === 4 && hook.cell.raw === t.traceNotCovered) {
              hook.cell.styles.textColor = [220, 53, 69];
            }
          }
        });
      }

      let safeTitle = session.title.replace(/[\/\\?%*:|"<>]/g, '-').substring(0, 30);
      doc.save(`TestCases_${safeTitle}.pdf`);
    } catch (err) { alert("Lỗi khi tải PDF: " + (err.response?.data?.message || err.message)); }
  };

  if (isAuthChecking) {
    return (
      <div className="app-loading-screen">
        <style>{appCss}</style>
        <div className="app-spinner" />
        <div style={{ marginTop: '16px', fontWeight: 700 }}>🧪 AI TestGen</div>
        <div style={{ fontSize: '14px', color: '#64748b', marginTop: '4px' }}>{t.checkingAuth}</div>
      </div>
    );
  }

  if (!currentUser) {
    // Trang chủ công khai: xem giới thiệu & test case nổi bật, mọi thao tác đều yêu cầu đăng nhập
    return <LandingPage language={language} setLanguage={setLanguage} onLogin={handleLogin} />;
  }

  const firstName = (currentUser.name || '').trim().split(/\s+/).pop() || '';
  const activeSession = chatSessions.find(s => s.id === (activeSessionId || currentSessionId));
  const headerTitle = isSharedView ? t.sharedTitle : (activeSessionId && activeSession ? activeSession.title : t.title);
  const showComposer = !activeSessionId && !isSharedView;

  return (
    <div className="app-root">
      <style>{appCss}</style>

      {activeMenuId && <div onClick={() => setActiveMenuId(null)} className="app-backdrop" style={{ zIndex: 25 }}></div>}
      {showSettingsMenu && <div onClick={() => setShowSettingsMenu(false)} className="app-backdrop" style={{ zIndex: 25 }}></div>}
      {showPlusMenu && <div onClick={() => setShowPlusMenu(false)} className="app-backdrop" style={{ zIndex: 8 }}></div>}

      {/* ================= SHARE MODAL ================= */}
      {shareModalSession && (
        <div className="app-overlay" onClick={() => setShareModalSession(null)}>
          <div className="app-modal" onClick={(e) => e.stopPropagation()}>
            <div style={{ height: '3px', background: 'linear-gradient(90deg, #1f4fd8, #7b4fe0)' }} />
            <div style={{ padding: '22px 24px' }}>
              <button onClick={() => setShareModalSession(null)} className="app-modal-close" aria-label="close">✕</button>
              <h3 style={{ margin: '0 0 6px 0', fontSize: '19px' }}>🔗 {t.shareTitle}</h3>
              <p style={{ margin: '0 0 18px 0', color: '#6c757d', fontSize: '14px' }}>{shareModalSession.title}</p>
              <div className="app-share-box">
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '14px', userSelect: 'all' }}>
                  {window.location.origin + '/?share=' + shareModalSession.id}
                </span>
                <button onClick={handleCopyLink} className="app-btn app-btn-primary" style={{ marginLeft: '10px' }}>⧉ {t.shareCopy}</button>
              </div>
              <p style={{ fontSize: '13px', color: '#6c757d', marginTop: '18px', lineHeight: 1.6, marginBottom: 0 }}>ⓘ {t.shareNote}</p>
            </div>
          </div>
        </div>
      )}

      {/* ================= SIDEBAR ================= */}
      {!isSharedView && (
        <>
          {isSidebarOpen && <div className="app-sidebar-scrim" onClick={() => setIsSidebarOpen(false)} />}
          {/* Sidebar nằm trên lớp phủ đóng menu; bấm chỗ trống trong sidebar cũng đóng menu */}
          <aside className={'app-sidebar' + (isSidebarOpen ? '' : ' collapsed')}
            onClick={() => { if (activeMenuId) setActiveMenuId(null); if (showSettingsMenu) setShowSettingsMenu(false); }}>
            <div className="app-sidebar-inner">
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 800, fontSize: '19px', whiteSpace: 'nowrap' }}>
                  <span style={{ fontSize: '22px' }}>🧪</span> AI TestGen
                </div>
                <button onClick={() => setIsSidebarOpen(false)} className="app-icon-btn app-icon-btn-light" title="Thu gọn">«</button>
              </div>

              <button onClick={handleNewChat} className="app-btn app-btn-new">
                <span style={{ fontSize: '18px', lineHeight: 1 }}>+</span> {t.newChat}
              </button>

              <div className="app-side-search">
                <span style={{ opacity: 0.8 }}>🔍</span>
                <input type="text" placeholder={t.search} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
              </div>

              <div className="app-side-label">{t.history}</div>

              <div className="app-history">
                {filteredHistory.length === 0 && <div style={{ fontSize: '13px', opacity: 0.7, padding: '8px 10px' }}>{t.noHistory}</div>}
                {filteredHistory.map(item => {
                  const pinned = item.pinned === true || item.isPinned === true;
                  return (
                    <div key={item.id} className={'app-history-item' + (activeSessionId === item.id ? ' active' : '')}>
                      {editingSessionId === item.id ? (
                        <input type="text" value={editTitle} onChange={(e) => setEditTitle(e.target.value)} onBlur={() => handleSaveRename(item.id)} onKeyDown={(e) => { if (e.key === 'Enter') handleSaveRename(item.id); }} autoFocus className="app-rename-input" onClick={(e) => e.stopPropagation()} />
                      ) : (
                        <div onClick={() => { handleSelectSession(item.id); if (window.innerWidth < 768) setIsSidebarOpen(false); }} className="app-history-title" title={item.title}>
                          <span style={{ marginRight: '6px' }}>{pinned ? '📌' : '💬'}</span>{item.title}
                        </div>
                      )}
                      <button onClick={(e) => { e.stopPropagation(); setShowSettingsMenu(false); setActiveMenuId(activeMenuId === item.id ? null : item.id); }} className="app-history-more">⋮</button>

                      {activeMenuId === item.id && (
                        <div className="app-menu" style={{ top: '38px', right: '6px' }}>
                          <div onClick={(e) => { e.stopPropagation(); setShareModalSession(item); setActiveMenuId(null); }} className="app-menu-item">🔗 {t.menuShare}</div>
                          <div onClick={(e) => { e.stopPropagation(); handleTogglePin(item); }} className="app-menu-item">{pinned ? '📍 ' + t.menuUnpin : '📌 ' + t.menuPin}</div>
                          <div onClick={(e) => { e.stopPropagation(); handleStartRename(item); }} className="app-menu-item">✏️ {t.menuRename}</div>
                          <div className="app-menu-sep" />
                          <div onClick={(e) => { e.stopPropagation(); handleSidebarExportPDF(item); }} className="app-menu-item">📄 {t.menuPdf}</div>
                          <div onClick={(e) => { e.stopPropagation(); handleSidebarExportExcel(item); }} className="app-menu-item">📊 {t.menuExcel}</div>
                          <div className="app-menu-sep" />
                          <div onClick={(e) => { e.stopPropagation(); handleDeleteSession(item.id); }} className="app-menu-item" style={{ color: '#dc3545' }}>🗑️ {t.menuDelete}</div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            <div className="app-user">
              {currentUser.picture ? (
                <img src={currentUser.picture} alt="Avatar" referrerPolicy="no-referrer" style={{ width: '38px', height: '38px', borderRadius: '50%', marginRight: '10px', border: '1px solid #e8ecf2' }} />
              ) : (
                <div style={{ width: '38px', height: '38px', borderRadius: '50%', marginRight: '10px', background: '#ff8a00', color: '#fff', fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>{(firstName || currentUser.email || '?').charAt(0).toUpperCase()}</div>
              )}
              <div style={{ flex: 1, overflow: 'hidden' }}>
                <div style={{ fontWeight: 600, fontSize: '14px', whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>{currentUser.name}</div>
                <div className="app-user-email" style={{ fontSize: '12px', whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>{currentUser.email}</div>
              </div>
              <button onClick={(e) => { e.stopPropagation(); setActiveMenuId(null); setShowSettingsMenu(!showSettingsMenu); }} className="app-icon-btn app-icon-btn-light" title="Cài đặt">⚙️</button>
              {showSettingsMenu && (
                <div className="app-menu" style={{ bottom: '64px', right: '12px', zIndex: 31 }}>
                  <div onClick={(e) => { e.stopPropagation(); handleLogout(); }} className="app-menu-item" style={{ color: '#dc3545', fontWeight: 600 }}>🚪 {t.logout}</div>
                </div>
              )}
            </div>
          </aside>
        </>
      )}

      {/* ================= MAIN ================= */}
      <main className="app-main">
        <header className="app-topbar">
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
            {!isSharedView && !isSidebarOpen && (<button onClick={() => setIsSidebarOpen(true)} className="app-icon-btn" title="Menu">☰</button>)}
            {isSharedView && (
              <button onClick={handleExitSharedView} className="app-btn app-btn-soft">{t.exitShare}</button>
            )}
            <span className="app-topbar-title" title={headerTitle}>{headerTitle}</span>
          </div>
          <select value={language} onChange={(e) => { setLanguage(e.target.value); setError(''); }} className="app-lang">
            <option value="vi">🇻🇳 Tiếng Việt</option>
            <option value="en">🇺🇸 English</option>
          </select>
        </header>

        <div className="app-content">
          {/* ---------- Ô nhập User Story (banner giống trang chủ) ---------- */}
          {showComposer && (
            <section className="app-hero">
              <h1 className="app-hero-title">{t.heroHello}{firstName ? ', ' : ''}<span className="app-grad">{firstName}</span> 👋</h1>
              <p style={{ margin: '0 auto 24px', fontSize: '15px', lineHeight: 1.6, maxWidth: '600px' }}>{t.heroSub}</p>
              <div className="app-composer">
                <textarea rows={3} placeholder={t.placeholder} value={userStory} onChange={(e) => setUserStory(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !loading) handleGenerate(); }} />
                <div className="app-composer-bar">
                  <div style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <button onClick={() => setShowPlusMenu(!showPlusMenu)} className="app-plus">+</button>
                    {showPlusMenu && (
                      <div className="app-menu" style={{ bottom: '46px', left: 0, top: 'auto' }}>
                        <div onClick={handleFakeUpload} className="app-menu-item">📁 Tải tệp lên...</div>
                      </div>
                    )}
                    <span className="app-hint">Ctrl + Enter</span>
                  </div>
                  <button onClick={handleGenerate} disabled={loading} className="app-btn app-btn-orange app-btn-lg">
                    {loading ? <><span className="app-spinner app-spinner-sm" /> {t.btnLoading}</> : '✨ ' + t.btnGenerate}
                  </button>
                </div>
              </div>
              <div className="app-chips" style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '16px' }}>
                {t.suggestions.map((s, i) => (
                  <button key={i} className="app-chip" onClick={() => setUserStory(s.text)}>{s.label}</button>
                ))}
              </div>
              {error && <div className="app-error">{error}</div>}
            </section>
          )}
          {!showComposer && error && <div className="app-error" style={{ marginBottom: '16px' }}>{error}</div>}

          {/* ---------- Danh sách ---------- */}
          {!(showComposer && testCases.length === 0 && !loading) && (
          <section className="app-card">
            <div className="app-card-head">
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
                <h2 style={{ margin: 0, fontSize: '19px' }}>{t.listTitle}</h2>
                <span className="app-count">{testCases.length}</span>
                {!isSharedView && activeTab === 'testcases' && testCases.length > 0 && <span style={{ fontSize: '13px', color: '#6c757d' }}>✎ {t.editHint}</span>}
              </div>
              <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                {hasUnsavedChanges && (
                  <button onClick={handleSaveChangesDB} disabled={loading} className="app-btn app-btn-primary app-pulse">{t.btnSave}</button>
                )}
                {testCases.length > 0 && (
                  <button onClick={handleMainExportExcel} className="app-btn app-btn-green">{t.btnExport}</button>
                )}
              </div>
            </div>

            {testCases.length > 0 && (
              <div className="app-tabs">
                {[['testcases', t.tabTestCases], ['trace', t.tabTrace]].map(([key, label]) => {
                  const cov = key === 'trace' && requirements.length > 0 ? computeCoverage(requirements, testCases) : null;
                  return (
                    <button key={key} onClick={() => setActiveTab(key)} className={'app-tab' + (activeTab === key ? ' active' : '')}>
                      {label}{cov && <span className="app-tab-badge" style={{ backgroundColor: cov.percent >= 80 ? '#d1e7dd' : cov.percent >= 50 ? '#ffe5d0' : '#f8d7da' }}>{cov.percent}%</span>}
                    </button>
                  );
                })}
              </div>
            )}

            {loading && testCases.length === 0 ? (
              <div className="app-empty">
                <div className="app-spinner" style={{ borderColor: '#dbe4ff', borderTopColor: '#1f4fd8' }} />
                <div style={{ fontWeight: 600, marginTop: '14px', color: '#212529' }}>{t.generatingTitle}</div>
                <div style={{ fontSize: '14px', marginTop: '4px' }}>{t.generatingSub}</div>
              </div>
            ) : testCases.length > 0 && activeTab === 'trace' ? (
              <TraceabilityPanel
                t={t}
                requirements={requirements}
                testCases={testCases}
                readOnly={isSharedView}
                busy={traceBusy}
                onToggleLink={handleToggleLink}
                onAnalyze={handleAnalyzeTraceability}
                onAddRequirement={handleAddRequirement}
                onUpdateRequirement={handleUpdateRequirement}
                onDeleteRequirement={handleDeleteRequirement}
              />
            ) : testCases.length === 0 ? (
              <div className="app-empty">
                <div style={{ fontSize: '42px', marginBottom: '8px' }}>🧪</div>
                <div style={{ fontWeight: 600, color: '#212529' }}>{t.emptyList}</div>
                {showComposer && <div style={{ fontSize: '14px', marginTop: '4px' }}>{t.emptyHint}</div>}
              </div>
            ) : (
              <div className="app-table-wrap">
                <table className="app-table">
                  <thead>
                    <tr>
                      <th style={{ width: '80px' }}>{t.colId}</th>
                      <th style={{ width: '19%' }}>{t.colSummary}</th>
                      <th style={{ width: '16%' }}>{t.colPrecondition}</th>
                      <th style={{ width: '27%' }}>{t.colSteps}</th>
                      <th style={{ width: '19%' }}>{t.colExpected}</th>
                      <th style={{ width: '110px' }}>{t.colReqs}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {testCases.map((tc, index) => (
                      <tr key={tc.dbId || index}>
                        <td><span className="app-tc-id">{tc.id}</span></td>
                        <td style={{ fontWeight: 500 }}>{renderEditableCell(tc, 'summary')}</td>
                        <td>{renderEditableCell(tc, 'precondition')}</td>
                        <td>{renderEditableCell(tc, 'steps')}</td>
                        <td>{renderEditableCell(tc, 'expected_result')}</td>
                        <td>{renderEditableCell(tc, 'requirementIds')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
          )}
        </div>
      </main>
    </div>
  );
}

// ================= STYLE (đồng bộ màu với trang chủ) =================
const appCss = `
:root { --brand: #1f4fd8; --brand-soft: #eef3ff; --accent: #ff8a00; --ink: #0f172a; --muted: #64748b; --line: #e8ecf2; --bg: #f7f9fc; }
.app-root { display: flex; height: 100vh; width: 100vw; overflow: hidden; font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; background: var(--bg); color: var(--ink); }
.app-backdrop { position: fixed; inset: 0; }
.app-loading-screen { height: 100vh; width: 100vw; display: flex; flex-direction: column; align-items: center; justify-content: center; font-family: system-ui, sans-serif; color: var(--ink); background: var(--bg); }
.app-spinner { width: 34px; height: 34px; border-radius: 50%; border: 3px solid #dbe4ff; border-top-color: var(--brand); animation: app-spin .8s linear infinite; display: inline-block; box-sizing: border-box; }
.app-spinner-sm { width: 15px; height: 15px; border-width: 2px; border-color: rgba(255,255,255,0.45); border-top-color: #fff; vertical-align: -3px; }
@keyframes app-spin { to { transform: rotate(360deg); } }
.app-grad { background: linear-gradient(120deg, #1f4fd8, #7b4fe0); -webkit-background-clip: text; background-clip: text; color: transparent; }

/* ----- Sidebar ----- */
.app-sidebar { width: 272px; flex-shrink: 0; display: flex; flex-direction: column; background: #fff; border-right: 1px solid var(--line); transition: width .25s ease; overflow: hidden; position: relative; z-index: 26; }
.app-sidebar.collapsed { width: 0; border-right: none; }
.app-sidebar-inner { padding: 18px 14px 0; min-width: 272px; box-sizing: border-box; display: flex; flex-direction: column; flex: 1; overflow: hidden; }
.app-side-search { display: flex; align-items: center; gap: 8px; background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 8px 12px; margin: 14px 0 20px; transition: border-color .15s, box-shadow .15s; }
.app-side-search:focus-within { border-color: #b9c8f5; box-shadow: 0 0 0 3px rgba(31,79,216,0.08); }
.app-side-search input { flex: 1; min-width: 0; background: none; border: none; outline: none; color: var(--ink); font-size: 14px; }
.app-side-search input::placeholder { color: #94a3b8; }
.app-side-label { font-size: 11px; font-weight: 700; letter-spacing: .8px; text-transform: uppercase; color: #94a3b8; margin: 0 0 6px 10px; }
.app-history { flex: 1; overflow-y: auto; margin: 0 -4px 12px; padding: 0 4px; scrollbar-width: thin; scrollbar-color: #e2e8f0 transparent; }
.app-history-item { position: relative; display: flex; align-items: center; padding: 8px 6px 8px 10px; border-radius: 8px; margin-bottom: 2px; transition: background .15s; color: #334155; }
.app-history-item:hover { background: #f4f6fa; }
.app-history-item.active { background: var(--brand-soft); color: var(--brand); }
.app-history-title { flex: 1; cursor: pointer; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.app-history-item.active .app-history-title { font-weight: 600; }
.app-history-more { background: none; border: none; color: inherit; opacity: 0; cursor: pointer; font-size: 16px; padding: 0 6px; border-radius: 6px; transition: opacity .15s; }
.app-history-item:hover .app-history-more, .app-history-item.active .app-history-more { opacity: .7; }
.app-rename-input { flex: 1; padding: 5px 8px; border-radius: 6px; border: 1px solid var(--brand); outline: none; font-size: 14px; min-width: 0; box-shadow: 0 0 0 3px rgba(31,79,216,0.1); }
.app-user { padding: 12px 14px; display: flex; align-items: center; border-top: 1px solid var(--line); min-width: 272px; box-sizing: border-box; position: relative; color: var(--ink); }
.app-user-email { color: var(--muted); }
.app-sidebar-scrim { display: none; }

/* ----- Menu ----- */
.app-menu { position: absolute; background: #fff; color: var(--ink); border-radius: 12px; box-shadow: 0 12px 32px rgba(15,23,42,0.12); z-index: 30; width: 210px; padding: 6px; border: 1px solid var(--line); }
.app-menu-item { padding: 8px 10px; font-size: 14px; cursor: pointer; border-radius: 8px; transition: background .15s; }
.app-menu-item:hover { background: #f4f6fa; }
.app-menu-sep { height: 1px; background: var(--line); margin: 4px 6px; }

/* ----- Buttons ----- */
.app-btn { display: inline-flex; align-items: center; gap: 6px; border: 1px solid transparent; border-radius: 10px; padding: 8px 14px; font-weight: 600; font-size: 14px; cursor: pointer; transition: background .15s, border-color .15s, box-shadow .15s, transform .15s; white-space: nowrap; font-family: inherit; }
.app-btn:disabled { opacity: .65; cursor: not-allowed; }
.app-btn-primary { background: var(--brand); color: #fff; }
.app-btn-primary:hover:not(:disabled) { background: #1a43bb; }
.app-btn-orange { background: var(--accent); color: #fff; box-shadow: 0 6px 16px rgba(255,138,0,0.28); }
.app-btn-orange:hover:not(:disabled) { background: #f07c00; transform: translateY(-1px); }
.app-btn-green { background: #fff; color: #15803d; border-color: #cfe9d9; }
.app-btn-green:hover { background: #f0fbf4; }
.app-btn-soft { background: var(--brand-soft); color: var(--brand); }
.app-btn-new { width: 100%; justify-content: center; background: #fff; color: var(--ink); border-color: var(--line); padding: 10px; }
.app-btn-new:hover { border-color: #c7d2e5; background: #f8fafc; }
.app-btn-lg { border-radius: 24px; padding: 10px 22px; font-size: 15px; }
.app-pulse { animation: app-pulse 1.8s ease-in-out infinite; }
@keyframes app-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(31,79,216,0.35); } 50% { box-shadow: 0 0 0 5px rgba(31,79,216,0); } }
.app-icon-btn { width: 34px; height: 34px; border-radius: 9px; border: 1px solid var(--line); background: #fff; color: #475569; font-size: 16px; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; transition: background .15s; }
.app-icon-btn:hover { background: #f4f6fa; }
.app-icon-btn-light { border-color: transparent; background: transparent; }

/* ----- Main ----- */
.app-main { flex: 1; display: flex; flex-direction: column; height: 100vh; overflow-y: auto; min-width: 0; }
.app-topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 28px; background: rgba(247,249,252,0.85); backdrop-filter: blur(10px); border-bottom: 1px solid transparent; }
.app-topbar-title { font-size: 15px; font-weight: 600; color: #334155; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.app-lang { padding: 6px 10px; border-radius: 9px; border: 1px solid var(--line); background: #fff; color: #334155; font-weight: 500; cursor: pointer; font-size: 13px; }
.app-content { max-width: 1080px; width: 100%; margin: 0 auto; padding: 20px 28px 48px; box-sizing: border-box; }

.app-hero { text-align: center; padding: 9vh 0 28px; margin: 0 auto 8px; max-width: 760px; }
.app-hero-title { margin: 0 0 8px 0; font-size: 34px; font-weight: 800; letter-spacing: -0.8px; color: var(--ink); }
.app-hero p { color: var(--muted) !important; opacity: 1 !important; }
.app-composer { background: #fff; border-radius: 18px; padding: 14px 14px 12px; border: 1px solid var(--line); box-shadow: 0 10px 30px rgba(15,23,42,0.06); text-align: left; transition: border-color .15s, box-shadow .15s; }
.app-composer:focus-within { border-color: #b9c8f5; box-shadow: 0 10px 30px rgba(31,79,216,0.10); }
.app-composer textarea { width: 100%; border: none; outline: none; resize: none; font-size: 15px; line-height: 1.55; font-family: inherit; color: var(--ink); box-sizing: border-box; background: transparent; }
.app-composer textarea::placeholder { color: #94a3b8; }
.app-composer-bar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 6px; padding-top: 8px; flex-wrap: wrap; }
.app-plus { width: 34px; height: 34px; border-radius: 50%; border: 1px solid var(--line); background: #fff; color: #475569; font-size: 18px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.app-plus:hover { background: #f4f6fa; }
.app-hint { font-size: 12px; color: #94a3b8; }
.app-hero .app-chips { justify-content: center; }
.app-chip { background: #fff; color: #475569; border: 1px solid var(--line); border-radius: 20px; padding: 6px 14px; font-size: 13px; font-weight: 500; cursor: pointer; transition: border-color .15s, color .15s, background .15s; font-family: inherit; }
.app-chip:hover { border-color: #b9c8f5; color: var(--brand); background: var(--brand-soft); }
.app-error { margin-top: 12px; background: #fef2f2; color: #b91c1c; padding: 10px 14px; border-radius: 10px; font-size: 14px; border: 1px solid #fecaca; text-align: left; }

.app-card { background: #fff; border-radius: 16px; padding: 22px; border: 1px solid var(--line); }
.app-card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
.app-card-head h2 { font-size: 17px !important; font-weight: 700; }
.app-count { background: #f1f5f9; color: #475569; font-weight: 600; font-size: 12px; padding: 2px 9px; border-radius: 10px; }
.app-tabs { display: flex; gap: 22px; border-bottom: 1px solid var(--line); margin-bottom: 18px; flex-wrap: wrap; }
.app-tab { padding: 10px 2px; border: none; background: none; color: var(--muted); font-weight: 600; font-size: 14px; cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; transition: color .15s, border-color .15s; font-family: inherit; }
.app-tab:hover { color: var(--ink); }
.app-tab.active { color: var(--brand); border-bottom-color: var(--brand); }
.app-tab-badge { margin-left: 8px; padding: 1px 7px; border-radius: 10px; font-size: 11px; color: var(--ink); }
.app-empty { text-align: center; padding: 52px 20px; border-radius: 14px; border: 1px dashed #d8dfea; color: var(--muted); }

.app-table-wrap { overflow-x: auto; border-radius: 12px; border: 1px solid var(--line); }
.app-table { width: 100%; border-collapse: collapse; text-align: left; font-size: 14px; }
.app-table thead th { background: #fafbfd; color: #64748b; font-size: 11px; text-transform: uppercase; letter-spacing: .6px; padding: 11px 12px; font-weight: 700; border-bottom: 1px solid var(--line); white-space: nowrap; }
.app-table tbody td { padding: 10px; border-bottom: 1px solid #f1f4f8; vertical-align: top; color: #1e293b; }
.app-table tbody tr { transition: background .15s; }
.app-table tbody tr:hover { background: #fafbfd; }
.app-table tbody tr:last-child td { border-bottom: none; }
.app-tc-id { display: inline-block; color: var(--brand); font-weight: 700; font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--brand-soft); margin-top: 3px; white-space: nowrap; }

/* ----- Modal ----- */
.app-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.45); backdrop-filter: blur(2px); z-index: 50; display: flex; align-items: center; justify-content: center; padding: 16px; }
.app-modal { position: relative; background: #fff; border-radius: 16px; width: 100%; max-width: 520px; overflow: hidden; box-shadow: 0 24px 60px rgba(15,23,42,0.25); }
.app-modal-close { position: absolute; top: 16px; right: 14px; background: none; border: none; font-size: 16px; color: #94a3b8; cursor: pointer; }
.app-share-box { display: flex; align-items: center; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 6px 6px 6px 14px; }

/* ----- Mobile ----- */
@media (max-width: 768px) {
  .app-sidebar { position: fixed; top: 0; bottom: 0; left: 0; z-index: 40; box-shadow: 8px 0 30px rgba(15,23,42,0.15); }
  .app-sidebar-scrim { display: block; position: fixed; inset: 0; background: rgba(15,23,42,0.35); z-index: 39; }
  .app-topbar { padding: 10px 14px; }
  .app-content { padding: 8px 14px 32px; }
  .app-hero { padding: 20px 0 18px; }
  .app-hero-title { font-size: 26px; }
  .app-card { padding: 16px; }
}
`;

export default App;
