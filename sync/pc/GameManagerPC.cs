// 게임 관리 PC 프로그램: 게임 실행 시간 기록 + 오늘 게임 현황 창 (폰 앱과 같은 서버를 씁니다).
// 빌드: build.bat (윈도우에 기본으로 들어 있는 csc.exe 만 쓰므로 따로 설치할 것이 없습니다)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

public class Game { public string name = ""; public string process = ""; public string processPath = ""; public int dayStart = -1; }
public class Cfg
{
    public string server = "";
    public string key = "";
    public int intervalSec = 30;
    public int resetHour = 4;
    public int doneMin = 5;
    public List<Game> games = new List<Game>();
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
            bool tray = args.Contains("--tray");
            Application.Run(new MainForm(tray));
        }
    }
}

class MainForm : Form
{
    static readonly JavaScriptSerializer Ser = new JavaScriptSerializer();
    static readonly CultureInfo Inv = CultureInfo.InvariantCulture;
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    readonly string dir = Application.StartupPath;
    string CfgPath { get { return Path.Combine(dir, "config.json"); } }
    string StatePath { get { return Path.Combine(dir, "state.json"); } }

    Cfg cfg = new Cfg();
    Dictionary<string, long> state = new Dictionary<string, long>();
    HashSet<string> prevRunning = new HashSet<string>();
    HashSet<string> running = new HashSet<string>();
    int sinceUpload = 999;
    bool allowShow, exiting;

    NotifyIcon tray;
    ListView lv;
    Label meta;
    ComboBox doneBox;
    CheckBox autoRun;
    System.Windows.Forms.Timer tickTimer, refreshTimer;

    public MainForm(bool startHidden)
    {
        allowShow = !startHidden;
        Text = "게임 관리";
        Icon = SystemIcons.Application;
        Width = 760; Height = 520; MinimumSize = new Size(560, 320);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("맑은 고딕", 9.5f);

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 40, Padding = new Padding(8, 8, 8, 0), WrapContents = false };
        meta = new Label { AutoSize = true, Margin = new Padding(0, 6, 16, 0), Text = "불러오는 중…" };
        doneBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 56 };
        foreach (var m in new[] { "1", "3", "5", "10", "15", "30" }) doneBox.Items.Add(m);
        top.Controls.Add(meta);
        top.Controls.Add(new Label { AutoSize = true, Margin = new Padding(0, 6, 4, 0), Text = "완료 기준(분)" });
        top.Controls.Add(doneBox);
        var bRe = new Button { Text = "새로고침", AutoSize = true }; bRe.Click += (s, e) => Reload();
        top.Controls.Add(bRe);
        Controls.Add(top);

        var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 42, Padding = new Padding(8, 6, 8, 0), WrapContents = false };
        var bAdd = new Button { Text = "게임 추가", AutoSize = true }; bAdd.Click += (s, e) => AddGame();
        var bDel = new Button { Text = "선택 게임 제거", AutoSize = true }; bDel.Click += (s, e) => RemoveGame();
        var bSet = new Button { Text = "서버 설정", AutoSize = true }; bSet.Click += (s, e) => { Settings(); Reload(); };
        autoRun = new CheckBox { Text = "윈도우 시작 시 자동 실행", AutoSize = true, Margin = new Padding(12, 6, 0, 0) };
        autoRun.CheckedChanged += (s, e) => SetAutoRun(autoRun.Checked);
        bottom.Controls.AddRange(new Control[] { bAdd, bDel, bSet, autoRun });
        Controls.Add(bottom);

        lv = new ListView { Dock = DockStyle.Fill, View = View.Details, FullRowSelect = true, GridLines = false, MultiSelect = false };
        lv.Columns.Add("게임", 190); lv.Columns.Add("오늘", 70); lv.Columns.Add("상태", 100); lv.Columns.Add("기기별", 190); lv.Columns.Add("최근 7일", 120);
        Controls.Add(lv);
        lv.BringToFront();

        tray = new NotifyIcon { Icon = SystemIcons.Application, Text = "게임 관리", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("게임 현황 열기", null, (s, e) => ShowMain());
        menu.Items.Add("종료", null, (s, e) => { exiting = true; tray.Visible = false; Application.Exit(); });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (s, e) => ShowMain();

        LoadConfig();
        doneBox.SelectedItem = cfg.doneMin.ToString();
        if (doneBox.SelectedIndex < 0) doneBox.SelectedItem = "5";
        doneBox.SelectedIndexChanged += (s, e) => { cfg.doneMin = int.Parse((string)doneBox.SelectedItem); SaveConfig(); Reload(); };
        autoRun.Checked = IsAutoRun();
        LoadState();

        var h = Handle; // BeginInvoke 를 숨긴 상태에서도 쓰려면 창 핸들이 필요하다
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
    void ShowMain() { allowShow = true; Show(); WindowState = FormWindowState.Normal; Activate(); Reload(); }

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
    static bool IsRunning(Game g)
    {
        Process[] ps;
        try { ps = Process.GetProcessesByName(g.process); } catch { return false; }
        try
        {
            if (ps.Length == 0) return false;
            if (string.IsNullOrEmpty(g.processPath)) return true;
            foreach (var p in ps)
            {
                try { if (p.MainModule.FileName.IndexOf(g.processPath, StringComparison.OrdinalIgnoreCase) >= 0) return true; } catch { }
            }
            return false;
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
        foreach (var g in cfg.games) if (IsRunning(g)) running.Add(g.name);
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

    Dictionary<string, object> lastOverview; string lastError;
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
    static string Mins(long ms) { return (ms / 60000) + "분"; }

    void Fill(Dictionary<string, object> o, string err)
    {
        if (err != null) { meta.Text = "서버에 연결하지 못했어요 (" + err + ")"; }
        long need = cfg.doneMin * 60000L;
        var rows = new List<string[]>(); var doneFlags = new List<bool>(); var runFlags = new List<bool>();
        var seen = new HashSet<string>();
        var rowsData = new List<Tuple<bool, string, string[], bool>>();
        Dictionary<string, object> games = (o != null && o.ContainsKey("games")) ? o["games"] as Dictionary<string, object> : null;
        Action<string, Dictionary<string, object>> add = (name, g) =>
        {
            long tot = 0; var devs = new List<string>(); string wk = "";
            bool markDone = false;
            if (g != null)
            {
                var d = g.ContainsKey("devices") ? g["devices"] as Dictionary<string, object> : null;
                if (d != null) foreach (var kv in d) { long ms = L(kv.Value); tot += ms; devs.Add((kv.Key == "phone" ? "폰" : kv.Key == "pc" ? "PC" : kv.Key) + " " + Mins(ms)); }
                markDone = g.ContainsKey("done") && g["done"] is bool && (bool)g["done"];
                var w = g.ContainsKey("week") ? g["week"] as System.Collections.IEnumerable : null;
                if (w != null) wk = string.Concat(w.Cast<object>().Reverse().Select(x => L(x) >= need ? "■" : "□"));
            }
            bool done = tot >= need || markDone;
            bool run = cfg.games.Any(x => Norm(x.name) == Norm(name) && running.Contains(x.name));
            string title = g != null && g.ContainsKey("title") && !string.IsNullOrEmpty(g["title"] as string) ? (string)g["title"] : name;
            rowsData.Add(Tuple.Create(done, title, new[] { title, Mins(tot), run ? (done ? "완료 · 플레이 중" : "플레이 중") : (done ? "완료" : "아직 안 함"), string.Join(" · ", devs), wk }, run));
        };
        if (games != null) foreach (var kv in games) { seen.Add(kv.Key); add(kv.Key, kv.Value as Dictionary<string, object>); }
        foreach (var g in cfg.games) if (!seen.Contains(Norm(g.name))) { seen.Add(Norm(g.name)); add(g.name, null); }
        rowsData.Sort((a, b) => a.Item1 != b.Item1 ? a.Item1.CompareTo(b.Item1) : string.Compare(a.Item2, b.Item2, StringComparison.CurrentCulture));
        lv.BeginUpdate(); lv.Items.Clear();
        foreach (var r in rowsData)
        {
            var it = new ListViewItem(r.Item3);
            if (r.Item1) it.ForeColor = Color.FromArgb(40, 150, 90);
            if (r.Item4) it.Font = new Font(lv.Font, FontStyle.Bold);
            lv.Items.Add(it);
        }
        lv.EndUpdate();
        if (err == null) meta.Text = "오늘 " + rowsData.Count(r => r.Item1) + " / " + rowsData.Count + " 완료";
    }

    // ---- 게임 추가/제거/설정
    void AddGame()
    {
        using (var f = new Form { Text = "게임 추가", Width = 460, Height = 420, StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font })
        {
            var info = new Label { Left = 12, Top = 10, Width = 420, Height = 36, Text = "게임을 켠 상태에서 아래 목록(창이 있는 프로그램)에서 고르세요." };
            var list = new ListBox { Left = 12, Top = 48, Width = 420, Height = 220 };
            var items = new List<KeyValuePair<string, string>>();
            foreach (var p in Process.GetProcesses())
            {
                try { if (p.MainWindowTitle.Length > 0 && p.ProcessName != "GameManagerPC") items.Add(new KeyValuePair<string, string>(p.ProcessName, p.MainWindowTitle)); } catch { }
            }
            foreach (var it in items.GroupBy(i => i.Key).Select(g => g.First()).OrderBy(i => i.Key)) list.Items.Add(it.Key + "   —   " + it.Value);
            var lName = new Label { Left = 12, Top = 280, Width = 90, Text = "표시 이름" };
            var tName = new TextBox { Left = 104, Top = 276, Width = 328 };
            var lHour = new Label { Left = 12, Top = 312, Width = 90, Text = "하루 시작" };
            var cHour = new ComboBox { Left = 104, Top = 308, Width = 120, DropDownStyle = ComboBoxStyle.DropDownList };
            cHour.Items.Add("기본 (" + cfg.resetHour + "시)"); for (int h = 0; h < 24; h++) cHour.Items.Add(h + "시"); cHour.SelectedIndex = 0;
            var ok = new Button { Text = "추가", Left = 270, Top = 344, Width = 80, DialogResult = DialogResult.OK };
            var cancel = new Button { Text = "취소", Left = 352, Top = 344, Width = 80, DialogResult = DialogResult.Cancel };
            list.SelectedIndexChanged += (s, e) => { if (list.SelectedItem != null && tName.Text.Length == 0) tName.Text = ((string)list.SelectedItem).Split(new[] { "   —   " }, StringSplitOptions.None)[0]; };
            f.Controls.AddRange(new Control[] { info, list, lName, tName, lHour, cHour, ok, cancel });
            f.AcceptButton = ok; f.CancelButton = cancel;
            if (f.ShowDialog(this) != DialogResult.OK || list.SelectedItem == null) return;
            string proc = ((string)list.SelectedItem).Split(new[] { "   —   " }, StringSplitOptions.None)[0];
            string name = tName.Text.Trim().Length > 0 ? tName.Text.Trim() : proc;
            if (cfg.games.Any(g => Norm(g.name) == Norm(name))) { MessageBox.Show("같은 이름의 게임이 이미 있어요."); return; }
            cfg.games.Add(new Game { name = name, process = proc, dayStart = cHour.SelectedIndex == 0 ? -1 : cHour.SelectedIndex - 1 });
            SaveConfig(); Tick(); Reload();
        }
    }
    void RemoveGame()
    {
        if (lv.SelectedItems.Count == 0) { MessageBox.Show("지울 게임을 목록에서 먼저 고르세요."); return; }
        string title = lv.SelectedItems[0].Text;
        var g = cfg.games.FirstOrDefault(x => Norm(x.name) == Norm(title));
        if (g == null) { MessageBox.Show("이 게임은 폰에서만 기록된 거라 여기서 지울 수 없어요."); return; }
        if (MessageBox.Show("'" + g.name + "' 기록을 PC에서 그만할까요? (이미 올라간 기록은 남아요)", "게임 제거", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
        cfg.games.Remove(g); SaveConfig(); Reload();
    }
    void Settings()
    {
        using (var f = new Form { Text = "서버 설정", Width = 440, Height = 190, StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font })
        {
            var tS = new TextBox { Left = 90, Top = 16, Width = 320, Text = cfg.server };
            var tK = new TextBox { Left = 90, Top = 50, Width = 320, Text = cfg.key, UseSystemPasswordChar = true };
            var ok = new Button { Text = "저장", Left = 250, Top = 96, Width = 80, DialogResult = DialogResult.OK };
            var cancel = new Button { Text = "취소", Left = 332, Top = 96, Width = 80, DialogResult = DialogResult.Cancel };
            f.Controls.AddRange(new Control[] { new Label { Left = 12, Top = 20, Width = 76, Text = "서버 주소" }, tS, new Label { Left = 12, Top = 54, Width = 76, Text = "키" }, tK, ok, cancel });
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

