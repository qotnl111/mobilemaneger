// 게임 관리 PC 프로그램: 게임 실행 시간 기록 + 오늘 게임 현황 카드 화면 (폰 앱과 같은 서버/같은 모양).
// 빌드: build.bat (윈도우에 기본으로 들어 있는 csc.exe 만 쓰므로 따로 설치할 것이 없습니다)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

public class Game { public string name = ""; public string process = ""; public string processPath = ""; public int dayStart = -1; public string exe = ""; }
public class Cfg
{
    public string server = "";
    public string key = "";
    public int intervalSec = 30;
    public int resetHour = 4;
    public int doneMin = 5;
    public List<Game> games = new List<Game>();
}

static class Theme
{
    public static readonly Color BG = Color.FromArgb(0x12, 0x14, 0x1A), CARD = Color.FromArgb(0x1D, 0x20, 0x29), LINE = Color.FromArgb(0x2C, 0x30, 0x3C),
        TEXT = Color.FromArgb(0xF2, 0xF3, 0xF7), SUB = Color.FromArgb(0x9A, 0xA0, 0xAE), TODO = Color.FromArgb(0xFF, 0xB5, 0x47),
        DONE = Color.FromArgb(0x4A, 0xDE, 0x80), SEL = Color.FromArgb(0x28, 0x2C, 0x39), DARKTXT = Color.FromArgb(0x1A, 0x13, 0x00);
    public static void Btn(Button b, bool primary)
    {
        b.FlatStyle = FlatStyle.Flat; b.FlatAppearance.BorderSize = primary ? 0 : 1; b.FlatAppearance.BorderColor = LINE;
        b.BackColor = primary ? TODO : CARD; b.ForeColor = primary ? DARKTXT : TEXT; b.AutoSize = true; b.Padding = new Padding(8, 2, 8, 2);
        b.Cursor = Cursors.Hand; b.UseVisualStyleBackColor = false;
    }
    public static void Dark(Control c)
    {
        c.BackColor = BG; c.ForeColor = TEXT;
        foreach (Control k in c.Controls)
        {
            if (k is TextBox || k is ListBox || k is ComboBox) { k.BackColor = CARD; k.ForeColor = TEXT; }
            else if (k is Button) Btn((Button)k, false);
            else { k.BackColor = BG; k.ForeColor = TEXT; }
        }
    }
    public static GraphicsPath Round(RectangleF r, float rad)
    {
        var p = new GraphicsPath(); float d = rad * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90); p.AddArc(r.X, r.Bottom - d, d, d, 90, 90); p.CloseFigure();
        return p;
    }
}

class Card
{
    public string name, title, devs = "", exe = "";
    public long tot; public bool done, run; public long[] week = new long[7];
}

class CardPanel : Panel
{
    public List<Card> Cards = new List<Card>();
    public long Need = 300000;
    public string SelectedName;
    public Func<string, Image> IconOf;
    const int CW = 176, CH = 258, GAP = 12, PAD = 16;

    public CardPanel() { DoubleBuffered = true; ResizeRedraw = true; AutoScroll = true; BackColor = Theme.BG; }
    int Cols() { return Math.Max(1, (ClientSize.Width - PAD * 2 + GAP) / (CW + GAP)); }
    Rectangle RectOf(int i)
    {
        int c = Cols();
        return new Rectangle(PAD + (i % c) * (CW + GAP) + AutoScrollPosition.X, PAD + (i / c) * (CH + GAP) + AutoScrollPosition.Y, CW, CH);
    }
    public void Relayout()
    {
        int rows = (Cards.Count + Cols() - 1) / Cols();
        AutoScrollMinSize = new Size(0, PAD * 2 + Math.Max(1, rows) * (CH + GAP));
        Invalidate();
    }
    protected override void OnResize(EventArgs e) { base.OnResize(e); Relayout(); }
    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e); Focus();
        string hit = null;
        for (int i = 0; i < Cards.Count; i++) if (RectOf(i).Contains(e.Location)) { hit = Cards[i].name; break; }
        SelectedName = hit == SelectedName ? null : hit; Invalidate();
    }
    public static string Fmt(long ms)
    {
        long h = ms / 3600000, m = (ms / 60000) % 60;
        return h > 0 ? h + "시간 " + m + "분" : m + "분";
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias; g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Theme.BG);
        using (var fName = new Font("맑은 고딕", 11f, FontStyle.Bold)) using (var fPill = new Font("맑은 고딕", 8.5f, FontStyle.Bold))
        using (var fSub = new Font("맑은 고딕", 8.5f)) using (var fBig = new Font("맑은 고딕", 17f, FontStyle.Bold))
        using (var sf = new StringFormat { Trimming = StringTrimming.EllipsisCharacter, FormatFlags = StringFormatFlags.NoWrap })
        {
            if (Cards.Count == 0)
            {
                var r = new RectangleF(PAD, PAD, 360, 90);
                using (var p = Theme.Round(r, 14)) { g.FillPath(new SolidBrush(Theme.CARD), p); g.DrawPath(new Pen(Theme.LINE), p); }
                g.DrawString("아래 '+ 게임 추가'로\n관리할 게임을 골라 주세요", fName, new SolidBrush(Theme.SUB), new RectangleF(r.X + 20, r.Y + 22, r.Width - 40, 60));
                return;
            }
            for (int i = 0; i < Cards.Count; i++)
            {
                var c = Cards[i]; var rc = RectOf(i);
                if (rc.Bottom < 0 || rc.Top > Height) continue;
                bool sel = c.name == SelectedName;
                using (var path = Theme.Round(rc, 14))
                {
                    g.FillPath(new SolidBrush(sel ? Theme.SEL : Theme.CARD), path);
                    using (var pen = new Pen(sel ? Theme.TEXT : (c.done ? Theme.LINE : Theme.TODO), sel ? 2f : 1.5f)) g.DrawPath(pen, path);
                }
                int x = rc.X + 14, y = rc.Y + 14, w = CW - 28;
                // 아이콘
                Image ic = IconOf != null ? IconOf(c.exe) : null;
                if (ic != null)
                {
                    var ia = new ImageAttributes(); ia.SetColorMatrix(new ColorMatrix { Matrix33 = c.done ? 0.55f : 1f });
                    g.DrawImage(ic, new Rectangle(x, y, 48, 48), 0, 0, ic.Width, ic.Height, GraphicsUnit.Pixel, ia);
                }
                else
                {
                    using (var p = Theme.Round(new RectangleF(x, y, 48, 48), 12)) g.FillPath(new SolidBrush(Theme.LINE), p);
                    string ch = (c.title ?? "?").Length > 0 ? c.title.Substring(0, 1) : "?";
                    g.DrawString(ch, fBig, new SolidBrush(Theme.SUB), new RectangleF(x, y + 5, 48, 40), new StringFormat { Alignment = StringAlignment.Center });
                }
                if (c.run)
                {
                    g.FillEllipse(new SolidBrush(Theme.DONE), rc.Right - 24, rc.Y + 16, 9, 9);
                    g.DrawString("플레이 중", fSub, new SolidBrush(Theme.DONE), new RectangleF(rc.Right - 92, rc.Y + 28, 78, 16), new StringFormat { Alignment = StringAlignment.Far });
                }
                // 이름
                g.DrawString(c.title, fName, new SolidBrush(Theme.TEXT), new RectangleF(x, y + 56, w, 22), sf);
                // 상태 알약
                string pill = c.done ? "✓ 플레이 완료" : "아직 안 함";
                var ps = g.MeasureString(pill, fPill); var pr = new RectangleF(x, y + 84, ps.Width + 12, 20);
                using (var p = Theme.Round(pr, 10)) g.FillPath(new SolidBrush(c.done ? Color.FromArgb(0x22, 0x4A, 0xDE, 0x80) : Theme.TODO), p);
                g.DrawString(pill, fPill, new SolidBrush(c.done ? Theme.DONE : Theme.DARKTXT), pr.X + 6, pr.Y + 2);
                // 오늘 시간
                g.DrawString("오늘", fSub, new SolidBrush(Theme.SUB), x, y + 112);
                g.DrawString(Fmt(c.tot), fBig, new SolidBrush(Theme.TEXT), x - 1, y + 126);
                // 진행 막대
                var tr = new RectangleF(x, y + 164, w, 6);
                using (var p = Theme.Round(tr, 3)) g.FillPath(new SolidBrush(Theme.LINE), p);
                float frac = Need <= 0 ? 1f : (float)Math.Min(1.0, (double)c.tot / Need);
                if (frac > 0.01f) using (var p = Theme.Round(new RectangleF(x, y + 164, Math.Max(6f, w * frac), 6), 3)) g.FillPath(new SolidBrush(c.done ? Theme.DONE : Theme.TODO), p);
                // 최근 7일
                float bw = (w - 6 * 3) / 7f;
                for (int d = 0; d < 7; d++)
                {
                    long ms = c.week[6 - d]; // 왼쪽이 오래된 날, 오른쪽이 오늘
                    g.FillRectangle(new SolidBrush(ms >= Need ? Theme.DONE : Theme.LINE), x + d * (bw + 3), y + 180, bw, 7);
                }
                g.DrawString(c.devs, fSub, new SolidBrush(Theme.SUB), new RectangleF(x, y + 194, w, 18), sf);
            }
        }
    }
}

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        bool created;
        using (var mx = new Mutex(true, "GameManagerPC-single", out created))
        {
            if (!created) return;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm(args.Contains("--tray")));
        }
    }
}

class MainForm : Form
{
    static readonly JavaScriptSerializer Ser = new JavaScriptSerializer();
    static readonly CultureInfo Inv = CultureInfo.InvariantCulture;
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int val, int size);

    readonly string dir = Application.StartupPath;
    string CfgPath { get { return Path.Combine(dir, "config.json"); } }
    string StatePath { get { return Path.Combine(dir, "state.json"); } }

    Cfg cfg = new Cfg();
    Dictionary<string, long> state = new Dictionary<string, long>();
    HashSet<string> prevRunning = new HashSet<string>();
    HashSet<string> running = new HashSet<string>();
    Dictionary<string, Image> icons = new Dictionary<string, Image>();
    int sinceUpload = 999;
    bool allowShow, exiting;

    NotifyIcon tray;
    CardPanel panel;
    Label title, meta;
    Label doneVal; Button doneMinus, donePlus;
    static readonly int[] DoneSteps = { 1, 3, 5, 10, 15, 30 };
    CheckBox autoRun;
    System.Windows.Forms.Timer tickTimer, refreshTimer;
    Dictionary<string, object> lastOverview; string lastError;

    public MainForm(bool startHidden)
    {
        allowShow = !startHidden;
        Text = "게임 관리";
        Icon = SystemIcons.Application;
        Width = 820; Height = 640; MinimumSize = new Size(420, 380);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("맑은 고딕", 9.5f);
        BackColor = Theme.BG; ForeColor = Theme.TEXT;

        var top = new Panel { Dock = DockStyle.Top, Height = 76, BackColor = Theme.BG };
        title = new Label { Text = "게임 관리", Font = new Font("맑은 고딕", 16f, FontStyle.Bold), AutoSize = true, Left = 16, Top = 10, ForeColor = Theme.TEXT };
        meta = new Label { Text = "불러오는 중…", AutoSize = true, Left = 18, Top = 46, ForeColor = Theme.SUB };
        doneMinus = new Button { Text = "−" }; Theme.Btn(doneMinus, false); doneMinus.AutoSize = false; doneMinus.Size = new Size(30, 30); doneMinus.Padding = Padding.Empty; doneMinus.Padding = Padding.Empty;
        donePlus = new Button { Text = "+" }; Theme.Btn(donePlus, false); donePlus.AutoSize = false; donePlus.Size = new Size(30, 30); donePlus.Padding = Padding.Empty;
        doneVal = new Label { AutoSize = false, Width = 44, Height = 30, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Theme.TEXT, Font = new Font("맑은 고딕", 10.5f, FontStyle.Bold) };
        doneMinus.Click += (s, e) => StepDone(-1); donePlus.Click += (s, e) => StepDone(1);
        var lbl = new Label { Text = "완료 기준(분)", AutoSize = true, ForeColor = Theme.SUB };
        var bRe = new Button { Text = "새로고침" }; Theme.Btn(bRe, false); bRe.Click += (s, e) => Reload();
        top.Controls.AddRange(new Control[] { title, meta, lbl, doneMinus, doneVal, donePlus, bRe });
        top.Resize += (s, e) =>
        {
            bRe.Left = top.Width - bRe.Width - 16; bRe.Top = 14;
            donePlus.Left = bRe.Left - donePlus.Width - 12; donePlus.Top = 14;
            doneVal.Left = donePlus.Left - doneVal.Width; doneVal.Top = 14;
            doneMinus.Left = doneVal.Left - doneMinus.Width; doneMinus.Top = 14;
            lbl.Left = doneMinus.Left - lbl.Width - 6; lbl.Top = 20;
        };
        Controls.Add(top);

        var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 52, Padding = new Padding(12, 10, 12, 0), WrapContents = false, BackColor = Theme.BG };
        var bAdd = new Button { Text = "+ 게임 추가" }; Theme.Btn(bAdd, true); bAdd.Click += (s, e) => AddGame();
        var bDel = new Button { Text = "선택 게임 제거" }; Theme.Btn(bDel, false); bDel.Click += (s, e) => RemoveGame();
        var bRen = new Button { Text = "폰과 이름 맞추기" }; Theme.Btn(bRen, false); bRen.Click += (s, e) => RenameToPhone();
        var bSet = new Button { Text = "서버 설정" }; Theme.Btn(bSet, false); bSet.Click += (s, e) => { Settings(); Reload(); };
        autoRun = new CheckBox { Text = "윈도우 시작 시 자동 실행", AutoSize = true, Margin = new Padding(14, 8, 0, 0), ForeColor = Theme.SUB };
        autoRun.CheckedChanged += (s, e) => SetAutoRun(autoRun.Checked);
        bottom.Controls.AddRange(new Control[] { bAdd, bDel, bRen, bSet, autoRun });
        Controls.Add(bottom);

        panel = new CardPanel { Dock = DockStyle.Fill, IconOf = IconOf };
        Controls.Add(panel);
        panel.BringToFront();

        tray = new NotifyIcon { Icon = SystemIcons.Application, Text = "게임 관리", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("게임 현황 열기", null, (s, e) => ShowMain());
        menu.Items.Add("종료", null, (s, e) => { exiting = true; tray.Visible = false; Application.Exit(); });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (s, e) => ShowMain();

        LoadConfig();
        if (Array.IndexOf(DoneSteps, cfg.doneMin) < 0) cfg.doneMin = 5;
        doneVal.Text = cfg.doneMin + "분"; panel.Need = cfg.doneMin * 60000L;
        autoRun.Checked = IsAutoRun();
        LoadState();

        var h = Handle; // BeginInvoke 를 숨긴 상태에서도 쓰려면 창 핸들이 필요하다
        int dark = 1; DwmSetWindowAttribute(Handle, 20, ref dark, 4); // 제목 표시줄도 어둡게
        tickTimer = new System.Windows.Forms.Timer { Interval = Math.Max(10, cfg.intervalSec) * 1000 };
        tickTimer.Tick += (s, e) => Tick();
        tickTimer.Start();
        refreshTimer = new System.Windows.Forms.Timer { Interval = 30000 };
        refreshTimer.Tick += (s, e) => { if (Visible) Reload(); };
        refreshTimer.Start();
        Shown += (s, e) => { if (cfg.server == "" || cfg.key == "") Settings(); Reload(); };
        Tick();
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(allowShow ? value : false); }
    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!exiting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); return; }
        base.OnFormClosing(e);
    }
    void StepDone(int d)
    {
        int i = Math.Max(0, Math.Min(DoneSteps.Length - 1, Array.IndexOf(DoneSteps, cfg.doneMin) + d));
        cfg.doneMin = DoneSteps[i]; doneVal.Text = cfg.doneMin + "분"; panel.Need = cfg.doneMin * 60000L; SaveConfig(); Fill(lastOverview, lastError);
    }
    void ShowMain() { allowShow = true; Show(); WindowState = FormWindowState.Normal; Activate(); Reload(); }

    Image IconOf(string exe)
    {
        if (string.IsNullOrEmpty(exe)) return null;
        Image im;
        if (icons.TryGetValue(exe, out im)) return im;
        try { using (var ic = Icon.ExtractAssociatedIcon(exe)) im = ic.ToBitmap(); } catch { im = null; }
        icons[exe] = im; return im;
    }

    // ---- 설정/상태 파일
    void LoadConfig()
    {
        try { if (File.Exists(CfgPath)) cfg = Ser.Deserialize<Cfg>(File.ReadAllText(CfgPath, Encoding.UTF8)); } catch { }
        if (cfg == null) cfg = new Cfg();
        if (cfg.games == null) cfg.games = new List<Game>();
        cfg.server = (cfg.server ?? "").TrimEnd('/');
    }
    void SaveConfig() { try { File.WriteAllText(CfgPath, Ser.Serialize(cfg), new UTF8Encoding(false)); } catch { } }
    void LoadState()
    {
        try { if (File.Exists(StatePath)) state = Ser.Deserialize<Dictionary<string, long>>(File.ReadAllText(StatePath, Encoding.UTF8)); } catch { }
        if (state == null) state = new Dictionary<string, long>();
    }
    void SaveState() { try { File.WriteAllText(StatePath, Ser.Serialize(state), new UTF8Encoding(false)); } catch { } }

    // ---- 시간 기록
    static string DayKey(int hour) { return DateTime.Now.AddHours(-hour).ToString("yyyy-MM-dd", Inv); }
    static bool IsRunning(Game g, out string exePath)
    {
        exePath = null;
        Process[] ps;
        try { ps = Process.GetProcessesByName(g.process); } catch { return false; }
        try
        {
            if (ps.Length == 0) return false;
            bool any = string.IsNullOrEmpty(g.processPath);
            foreach (var p in ps)
            {
                try
                {
                    string f = p.MainModule.FileName;
                    if (!string.IsNullOrEmpty(g.processPath) && f.IndexOf(g.processPath, StringComparison.OrdinalIgnoreCase) < 0) continue;
                    exePath = f; any = true; break;
                }
                catch { }
            }
            return any;
        }
        finally { foreach (var p in ps) p.Dispose(); }
    }
    Dictionary<string, object> Entry(string day, string game, long ms, long nowMs, int reset)
    {
        return new Dictionary<string, object> { { "day", day }, { "game", game }, { "ms", ms }, { "lastEnd", nowMs }, { "reset", reset } };
    }
    void Tick()
    {
        int tick = Math.Max(10, cfg.intervalSec);
        running = new HashSet<string>();
        bool cfgChanged = false;
        foreach (var g in cfg.games)
        {
            string exe;
            if (IsRunning(g, out exe))
            {
                running.Add(g.name);
                if (!string.IsNullOrEmpty(exe) && g.exe != exe) { g.exe = exe; cfgChanged = true; }
            }
        }
        if (cfgChanged) SaveConfig();
        long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var entries = new List<Dictionary<string, object>>();
        bool stopped = false;
        foreach (var g in cfg.games)
        {
            int hr = g.dayStart >= 0 && g.dayStart <= 23 ? g.dayStart : cfg.resetHour;
            string k = DayKey(hr) + "|" + g.name;
            if (running.Contains(g.name))
            {
                long cur; state.TryGetValue(k, out cur);
                state[k] = cur + tick * 1000L;
                entries.Add(Entry(DayKey(hr), g.name, state[k], nowMs, hr));
            }
            else if (prevRunning.Contains(g.name) && state.ContainsKey(k))
            {
                entries.Add(Entry(DayKey(hr), g.name, state[k], nowMs, hr)); // 방금 꺼짐: 마지막 시간 바로 올림
                stopped = true;
            }
        }
        string oldest = DateTime.Now.AddDays(-3).ToString("yyyy-MM-dd", Inv);
        foreach (var k in state.Keys.Where(k => string.CompareOrdinal(k.Split('|')[0], oldest) < 0).ToList()) state.Remove(k);
        SaveState();
        sinceUpload += tick;
        if (entries.Count > 0 && (sinceUpload >= 60 || stopped)) { Upload(entries); sinceUpload = 0; }
        prevRunning = running;
        if (Visible) Fill(lastOverview, lastError);
    }

    // ---- 서버 통신 (UI 를 막지 않도록 백그라운드에서)
    string Http(string method, string url, string body)
    {
        var req = (HttpWebRequest)WebRequest.Create(url);
        req.Method = method; req.Timeout = 15000; req.Proxy = null;
        req.Headers["x-gm-key"] = cfg.key;
        if (body != null)
        {
            var b = Encoding.UTF8.GetBytes(body);
            req.ContentType = "application/json; charset=utf-8"; req.ContentLength = b.Length;
            using (var s = req.GetRequestStream()) s.Write(b, 0, b.Length);
        }
        try
        {
            using (var r = (HttpWebResponse)req.GetResponse())
            using (var sr = new StreamReader(r.GetResponseStream(), Encoding.UTF8)) return sr.ReadToEnd();
        }
        catch (WebException ex)
        {
            var hr = ex.Response as HttpWebResponse;
            if (hr != null && (int)hr.StatusCode == 401) throw new Exception("키가 맞지 않아요");
            throw;
        }
    }
    void Upload(List<Dictionary<string, object>> entries)
    {
        if (cfg.server == "" || cfg.key == "") return;
        string url = cfg.server + "/api/play", body = Ser.Serialize(new Dictionary<string, object> { { "device", "pc" }, { "entries", entries } });
        ThreadPool.QueueUserWorkItem(_ => { try { Http("POST", url, body); } catch { } });
    }

    void Reload()
    {
        if (cfg.server == "" || cfg.key == "") { meta.Text = "서버 설정이 필요해요"; return; }
        string url = cfg.server + "/api/overview?local=" + DateTime.Now.ToString("yyyy-MM-dd'T'HH:mm", Inv);
        ThreadPool.QueueUserWorkItem(_ =>
        {
            Dictionary<string, object> o = null; string err = null;
            try { o = Ser.DeserializeObject(Http("GET", url, null)) as Dictionary<string, object>; } catch (Exception ex) { err = ex.Message; }
            try { BeginInvoke(new Action(() => { lastOverview = o; lastError = err; Fill(o, err); })); } catch { }
        });
    }
    static string Norm(string n) { var s = Regex.Replace(n ?? "", @"\s+", "").ToLowerInvariant(); return s.Length > 80 ? s.Substring(0, 80) : s; }
    static long L(object o) { return o == null ? 0 : Convert.ToInt64(o, Inv); }

    void Fill(Dictionary<string, object> o, string err)
    {
        long need = cfg.doneMin * 60000L; panel.Need = need;
        var cards = new List<Card>(); var seen = new HashSet<string>();
        Dictionary<string, object> games = (o != null && o.ContainsKey("games")) ? o["games"] as Dictionary<string, object> : null;
        Action<string, Dictionary<string, object>> add = (name, g) =>
        {
            var c = new Card { name = name, title = name };
            var cg = cfg.games.FirstOrDefault(x => Norm(x.name) == Norm(name));
            if (cg != null) { c.exe = cg.exe ?? ""; c.run = running.Contains(cg.name); c.title = cg.name; }
            bool markDone = false; var devs = new List<string>();
            if (g != null)
            {
                if (cg == null && g.ContainsKey("title") && !string.IsNullOrEmpty(g["title"] as string)) c.title = (string)g["title"];
                var d = g.ContainsKey("devices") ? g["devices"] as Dictionary<string, object> : null;
                if (d != null) foreach (var kv in d) { long ms = L(kv.Value); c.tot += ms; devs.Add((kv.Key == "phone" ? "폰" : kv.Key == "pc" ? "PC" : kv.Key) + " " + CardPanel.Fmt(ms)); }
                markDone = g.ContainsKey("done") && g["done"] is bool && (bool)g["done"];
                var w = g.ContainsKey("week") ? g["week"] as System.Collections.IEnumerable : null;
                if (w != null) { int i = 0; foreach (var x in w) { if (i < 7) c.week[i] = L(x); i++; } }
            }
            c.devs = string.Join(" · ", devs);
            c.done = c.tot >= need || markDone;
            cards.Add(c);
        };
        if (games != null) foreach (var kv in games) { seen.Add(kv.Key); add(kv.Key, kv.Value as Dictionary<string, object>); }
        foreach (var g in cfg.games) if (seen.Add(Norm(g.name))) add(g.name, null);
        cards.Sort((a, b) => a.done != b.done ? a.done.CompareTo(b.done) : string.Compare(a.title, b.title, StringComparison.CurrentCulture));
        panel.Cards = cards; panel.Relayout();
        if (err != null) meta.Text = "서버에 연결하지 못했어요 (" + err + ")";
        else meta.Text = "오늘 " + cards.Count(c => c.done) + " / " + cards.Count + " 완료 · " + cfg.doneMin + "분 이상 켜면 자동 완료";
    }

    // ---- 게임 추가/제거/설정
    void AddGame()
    {
        const string SEP = "   —   ";
        using (var f = new Form { Text = "게임 추가", Width = 470, Height = 430, StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font })
        {
            var info = new Label { Left = 12, Top = 10, Width = 430, Height = 36, Text = "게임을 켠 상태에서 아래 목록에서 고르세요. 폰에도 있는 게임이면 '표시 이름'을 폰 앱의 이름과 똑같이 맞춰야 한 카드로 합쳐져요(오른쪽 화살표로 폰 게임 목록 선택)." };
            var list = new ListBox { Left = 12, Top = 48, Width = 430, Height = 220, BorderStyle = BorderStyle.FixedSingle };
            var items = new List<KeyValuePair<string, string>>();
            foreach (var p in Process.GetProcesses())
            {
                try { if (p.MainWindowTitle.Length > 0 && p.ProcessName != "GameManagerPC") items.Add(new KeyValuePair<string, string>(p.ProcessName, p.MainWindowTitle)); } catch { }
            }
            foreach (var it in items.GroupBy(i => i.Key).Select(g => g.First()).OrderBy(i => i.Key)) list.Items.Add(it.Key + SEP + it.Value);
            var lName = new Label { Left = 12, Top = 282, Width = 90, Text = "표시 이름" };
            var tName = new ComboBox { Left = 104, Top = 278, Width = 338, DropDownStyle = ComboBoxStyle.DropDown, FlatStyle = FlatStyle.Flat };
            foreach (var t in PhoneOnlyTitles(true)) tName.Items.Add(t);
            string autoName = "";
            var lHour = new Label { Left = 12, Top = 316, Width = 90, Text = "하루 시작" };
            var cHour = new ComboBox { Left = 104, Top = 312, Width = 120, DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat };
            cHour.Items.Add("기본 (" + cfg.resetHour + "시)"); for (int h = 0; h < 24; h++) cHour.Items.Add(h + "시"); cHour.SelectedIndex = 0;
            var ok = new Button { Text = "추가", Left = 280, Top = 350, Width = 80, DialogResult = DialogResult.OK };
            var cancel = new Button { Text = "취소", Left = 362, Top = 350, Width = 80, DialogResult = DialogResult.Cancel };
            list.SelectedIndexChanged += (s, e) =>
            {
                if (list.SelectedItem == null || !(tName.Text == "" || tName.Text == autoName)) return;
                var parts = ((string)list.SelectedItem).Split(new[] { SEP }, StringSplitOptions.None);
                string np = Norm(parts[0]), nw = parts.Length > 1 ? Norm(parts[1]) : "";
                // 폰 게임 이름과 프로세스/창 제목이 비슷하면 자동으로 그 이름을 제안한다
                string guess = null;
                foreach (var t in PhoneOnlyTitles(true))
                {
                    string nt = Norm(t);
                    if (nt.Length >= 3 && (nt == np || nt == nw || (np.Length >= 3 && (nt.Contains(np) || np.Contains(nt))) || (nw.Length >= 3 && (nw.Contains(nt) || nt.Contains(nw))))) { guess = t; break; }
                }
                autoName = guess ?? parts[0]; tName.Text = autoName;
            };
            f.Controls.AddRange(new Control[] { info, list, lName, tName, lHour, cHour, ok, cancel });
            Theme.Dark(f); Theme.Btn(ok, true); ok.AutoSize = false; cancel.AutoSize = false; ok.Size = cancel.Size = new Size(80, 30);
            f.AcceptButton = ok; f.CancelButton = cancel;
            if (f.ShowDialog(this) != DialogResult.OK || list.SelectedItem == null) return;
            string proc = ((string)list.SelectedItem).Split(new[] { SEP }, StringSplitOptions.None)[0];
            string name = tName.Text.Trim().Length > 0 ? tName.Text.Trim() : proc;
            string exe = "";
            try { var ps = Process.GetProcessesByName(proc); if (ps.Length > 0) exe = ps[0].MainModule.FileName; } catch { }
            var same = cfg.games.FirstOrDefault(g => Norm(g.name) == Norm(name));
            if (same != null)
            {
                // 이미 등록된 게임이면 막지 않고, 어떤 프로그램을 이 게임으로 볼지만 바꿔 준다
                if (MessageBox.Show("'" + same.name + "'은(는) 이미 등록돼 있어요.\n이 게임의 실행 프로그램을 '" + proc + "'(으)로 바꿀까요?", "게임 추가", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
                same.process = proc; same.processPath = ""; if (exe != "") same.exe = exe;
                if (cHour.SelectedIndex != 0) same.dayStart = cHour.SelectedIndex - 1;
                SaveConfig(); Tick(); Reload(); return;
            }
            cfg.games.Add(new Game { name = name, process = proc, exe = exe, dayStart = cHour.SelectedIndex == 0 ? -1 : cHour.SelectedIndex - 1 });
            SaveConfig(); Tick(); Reload();
        }
    }
    List<string> PhoneOnlyTitles(bool all = false)
    {
        var res = new List<string>();
        var games = (lastOverview != null && lastOverview.ContainsKey("games")) ? lastOverview["games"] as Dictionary<string, object> : null;
        if (games == null) return res;
        foreach (var kv in games)
        {
            var g = kv.Value as Dictionary<string, object>; if (g == null) continue;
            var d = g.ContainsKey("devices") ? g["devices"] as Dictionary<string, object> : null;
            if (d == null || !d.ContainsKey("phone")) continue;
            if (!all && cfg.games.Any(x => Norm(x.name) == kv.Key)) continue;
            string t = g.ContainsKey("title") ? g["title"] as string : null;
            res.Add(string.IsNullOrEmpty(t) ? kv.Key : t);
        }
        return res;
    }
    void RenameToPhone()
    {
        if (panel.SelectedName == null) { MessageBox.Show("이름을 맞출 게임 카드를 먼저 클릭하세요."); return; }
        var g = cfg.games.FirstOrDefault(x => Norm(x.name) == Norm(panel.SelectedName));
        if (g == null) { MessageBox.Show("이 카드는 폰에서 온 게임이에요. PC에서 추가한 게임 카드를 고르세요."); return; }
        var titles = PhoneOnlyTitles();
        if (titles.Count == 0) { MessageBox.Show("이름을 맞출 폰 게임이 아직 없어요.\n폰 앱에서 동기화(설정 > PC·폰 동기화)를 한 번 한 뒤 새로고침하세요."); return; }
        using (var f = new Form { Text = "폰 게임과 이름 맞추기", Width = 400, Height = 360, StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font })
        {
            var info = new Label { Left = 12, Top = 10, Width = 360, Height = 36, Text = "'" + g.name + "' 와(과) 같은 게임을 폰 목록에서 고르세요." };
            var list = new ListBox { Left = 12, Top = 50, Width = 360, Height = 220, BorderStyle = BorderStyle.FixedSingle };
            foreach (var t in titles) list.Items.Add(t);
            var ok = new Button { Text = "맞추기", Left = 210, Top = 282, Width = 80, DialogResult = DialogResult.OK };
            var cancel = new Button { Text = "취소", Left = 292, Top = 282, Width = 80, DialogResult = DialogResult.Cancel };
            f.Controls.AddRange(new Control[] { info, list, ok, cancel });
            Theme.Dark(f); Theme.Btn(ok, true); ok.AutoSize = false; cancel.AutoSize = false; ok.Size = cancel.Size = new Size(80, 30);
            f.AcceptButton = ok; f.CancelButton = cancel;
            if (f.ShowDialog(this) != DialogResult.OK || list.SelectedItem == null) return;
            string nn = (string)list.SelectedItem, old = g.name;
            foreach (var k in state.Keys.Where(k => k.EndsWith("|" + old)).ToList()) { var nk = k.Substring(0, k.Length - old.Length) + nn; state[nk] = state[k]; state.Remove(k); }
            g.name = nn; panel.SelectedName = null; SaveConfig(); SaveState(); Tick(); Reload();
        }
    }
    void RemoveGame()
    {
        if (panel.SelectedName == null) { MessageBox.Show("지울 게임 카드를 먼저 클릭해서 고르세요."); return; }
        var g = cfg.games.FirstOrDefault(x => Norm(x.name) == Norm(panel.SelectedName));
        if (g == null) { MessageBox.Show("이 게임은 폰에서만 기록된 거라 여기서 지울 수 없어요."); return; }
        if (MessageBox.Show("'" + g.name + "' 기록을 PC에서 그만할까요? (이미 올라간 기록은 남아요)", "게임 제거", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
        cfg.games.Remove(g); panel.SelectedName = null; SaveConfig(); Reload();
    }
    void Settings()
    {
        using (var f = new Form { Text = "서버 설정", Width = 450, Height = 190, StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font })
        {
            var tS = new TextBox { Left = 90, Top = 16, Width = 330, Text = cfg.server, BorderStyle = BorderStyle.FixedSingle };
            var tK = new TextBox { Left = 90, Top = 50, Width = 330, Text = cfg.key, UseSystemPasswordChar = true, BorderStyle = BorderStyle.FixedSingle };
            var ok = new Button { Text = "저장", Left = 260, Top = 96, Width = 80, DialogResult = DialogResult.OK };
            var cancel = new Button { Text = "취소", Left = 342, Top = 96, Width = 80, DialogResult = DialogResult.Cancel };
            f.Controls.AddRange(new Control[] { new Label { Left = 12, Top = 20, Width = 76, Text = "서버 주소" }, tS, new Label { Left = 12, Top = 54, Width = 76, Text = "키" }, tK, ok, cancel });
            Theme.Dark(f); Theme.Btn(ok, true); ok.AutoSize = false; cancel.AutoSize = false; ok.Size = cancel.Size = new Size(80, 30);
            f.AcceptButton = ok; f.CancelButton = cancel;
            if (f.ShowDialog(this) != DialogResult.OK) return;
            cfg.server = tS.Text.Trim().TrimEnd('/'); cfg.key = tK.Text.Trim(); SaveConfig();
        }
    }

    // ---- 윈도우 시작 시 자동 실행
    bool IsAutoRun() { try { using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue("GameManagerPC") != null; } catch { return false; } }
    void SetAutoRun(bool on)
    {
        try
        {
            using (var k = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (on) k.SetValue("GameManagerPC", "\"" + Application.ExecutablePath + "\" --tray");
                else k.DeleteValue("GameManagerPC", false);
            }
        }
        catch { }
    }
}
