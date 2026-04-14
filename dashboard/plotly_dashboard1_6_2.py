
from __future__ import annotations
from urllib.parse import quote  # ← _make_kakao_open_url 제거 시엔 불필요

# ==============================
# ESM Dashboard (Query-on-Demand)
# ==============================
# - Do NOT load full tables at startup.
# - Only query when the UI actually needs something.
# - Interval (30s) refresh still runs, but each callback guards and queries
#   the minimal subset with WHERE conditions.
#
# How to run (recommended):
#   gunicorn -b 0.0.0.0:8050 plotly_dashboard1_6_2_FINAL_SAFE:server --workers 1
#
# Notes:
# - Uses the same DB as existing 1.5.4
# - Alarm log reading remains file-based (lightweight).
# - Abnormal-sensor list is computed with grouped MAX() queries (no full scan).

from datetime import datetime, timedelta, timezone
import pandas as pd
import json
from sqlalchemy import create_engine, text
import plotly.express as px
import dash
from dash import Dash, dcc, html, dash_table, Input, Output, State, MATCH, no_update
import dash_bootstrap_components as dbc

# ----------------------------------------------------------------------------
# [DB CONFIG]
# ----------------------------------------------------------------------------
DB_URL = "postgresql+psycopg2://kist:kist@localhost:5432/esmdb"
engine = create_engine(DB_URL, pool_pre_ping=True)

# ----------------------------------------------------------------------------
# [FILE-BASED LOGS]
# ----------------------------------------------------------------------------
ALARM_LOG_PATH = "/home/kist/esmServer/alarm_log.txt"
KAKAO_UUID_JSON = "/home/kist/Documents/kakao_uuid_map.json" 
def _load_kakao_uuid_json() -> dict[str, str]: 
    try:  
        with open(KAKAO_UUID_JSON, "r", encoding="utf-8") as f: 
            data = json.load(f) or {} # 문자열 정규화 
            return {str(k).strip(): str(v).strip() for k, v in data.items() if v} 
    except FileNotFoundError: 
        return {} 
    except Exception: 
        return {} 
    
def get_kakao_uuid_map(user_ids: list[str] | None) -> dict[str, str]: 
    """JSON에서 {user_id: kakao_uuid} 반환. user_ids가 주어지면 서브셋만.""" 
    full = _load_kakao_uuid_json() 
    if not user_ids: 
        return full 
    s = {str(u) for u in user_ids} 
    return {uid: full[uid] for uid in full.keys() & s}

def load_alarm_log() -> pd.DataFrame:
    try:
        df = pd.read_csv(ALARM_LOG_PATH, names=["user_id", "mode", "start_ms", "end_ms"])
    except FileNotFoundError:
        return pd.DataFrame(columns=["user_id", "mode", "start_ms", "end_ms", "start_time", "end_time"])
    df["start_time"] = pd.to_datetime(df["start_ms"], unit="ms")
    df["end_time"]   = pd.to_datetime(df["end_ms"],   unit="ms")
    return df

# ----------------------------------------------------------------------------
# [HELPERS]
# ----------------------------------------------------------------------------
def fetch_df(sql: str, params: dict | None = None) -> pd.DataFrame:
    """Lightweight helper to query a dataframe with SQLAlchemy (text)."""
    with engine.connect() as conn:
        return pd.read_sql(text(sql), conn, params=params)

def get_user_map_for(uids: list[str] | None) -> dict[str, str]:
    """Return {user_id: 'uid (name)'} only for the given IDs (or for all if None)."""
    if uids:
        sql = """
            SELECT user_id::text AS user_id, COALESCE(name, '') AS name
            FROM users
            WHERE user_id = ANY(:uids)
        """
        df = fetch_df(sql, {"uids": uids})
    else:
        sql = "SELECT user_id::text AS user_id, COALESCE(name, '') AS name FROM users"
        df = fetch_df(sql)

    def _label(row):
        uid = row["user_id"]
        nm  = (row.get("name") or "").strip()
        return f"{uid} ({nm})" if nm else uid

    return {r["user_id"]: _label(r) for _, r in df.iterrows()}


def get_all_user_options() -> list[dict]:
    """Used to populate dropdowns. Small table; ok to query directly."""
    sql = "SELECT user_id::text AS user_id, COALESCE(name, '') AS name FROM users ORDER BY user_id"
    df = fetch_df(sql)
    opts = [{"label": "전체 보기", "value": "ALL"}]
    for _, r in df.iterrows():

        nm = (r["name"] or "").strip()
        label = f"{r['user_id']} ({nm})" if nm else r["user_id"]
        opts.append({"label": label, "value": r["user_id"]})
    return opts

def _label_for(uid: str) -> str:
    m = get_user_map_for([uid])
    return m.get(uid, uid)

def get_locations(sensor_type: str, user_id: str) -> list[str]:
    """Locations come from the small sensor_binding table (fast)."""
    sql = """
        SELECT DISTINCT location
        FROM sensor_binding
        WHERE sensor_type = :stype AND user_id::text = :uid
        ORDER BY location
    """
    df = fetch_df(sql, {"stype": sensor_type, "uid": user_id})
    return [l for l in df["location"].dropna().astype(str).tolist()]

def _apply_range_df(df: pd.DataFrame, start, end) -> pd.DataFrame:
    if df.empty or start is None or end is None:
        return df
    s = pd.to_datetime(start)
    e = pd.to_datetime(end)
    return df[(pd.to_datetime(df["sensor_time"]) >= s) & (pd.to_datetime(df["sensor_time"]) <= e)]

# ----------------------------------------------------------------------------
# [ABNORMAL SENSOR LIST]
# ----------------------------------------------------------------------------
def query_abnormal_sensors(threshold_minutes: float = 1.0) -> pd.DataFrame:
    """
    Compute last received timestamp per sensor across 3 tables via grouped MAX(),
    join with binding+users, and filter by delay > threshold.
    """
    sql = """
    WITH last_tof AS (
        SELECT sd.sensor_id, MAX(sd.sensor_time) AS last_time
        FROM sensor_data sd
        GROUP BY sd.sensor_id
    ),
    last_with AS (
        SELECT w.sensor_id, MAX(w.sensor_time) AS last_time
        FROM sensor_with w
        GROUP BY w.sensor_id
    ),
    last_cam AS (
        SELECT c.sensor_id, MAX(c.sensor_time) AS last_time
        FROM sensorcam c
        GROUP BY c.sensor_id
    ),
    last_all AS (
        SELECT sensor_id, last_time FROM last_tof
        UNION ALL
        SELECT sensor_id, last_time FROM last_with
        UNION ALL
        SELECT sensor_id, last_time FROM last_cam
    ),
    last_by_sensor AS (
        SELECT sensor_id, MAX(last_time) AS last_time
        FROM last_all
        GROUP BY sensor_id
    )
    SELECT b.sensor_id::text AS sensor_id,
           b.user_id::text AS user_id,
           b.sensor_type::text AS sensor_type,
           COALESCE(b.location::text, '') AS location,
           COALESCE(u.name::text, '') AS name,
           lb.last_time
    FROM last_by_sensor lb
    JOIN sensor_binding b ON lb.sensor_id = b.sensor_id
    LEFT JOIN users u ON b.user_id = u.user_id
    """
    df = fetch_df(sql)
    if df.empty:
        return df

    now = pd.Timestamp.now(tz="Asia/Seoul").to_pydatetime().replace(tzinfo=None)
    df["sensor_time"] = pd.to_datetime(df["last_time"])
    df["delay_minutes"] = (now - df["sensor_time"]).dt.total_seconds() / 60.0
    df = df[df["delay_minutes"] > threshold_minutes].copy()

    # Display columns
    def label_user(uid, nm):
        nm = (nm or "").strip()
        return f"{uid} ({nm})" if nm else str(uid)

    df["user_label"]   = df.apply(lambda r: label_user(r["user_id"], r.get("name")), axis=1)
    df["sensor_label"] = df["sensor_type"] + " (" + df["sensor_id"].astype(str) + ")"
    df["display"]      = df["user_label"] + " " + df["sensor_label"]
    return df


def query_sensor_status_all(threshold_minutes: float = 1.0) -> pd.DataFrame:
    """
    Return status for ALL bound sensors with last reception time.
    - Green (is_ok=True): last_time within threshold
    - Red   (is_ok=False): delayed beyond threshold or no last_time
    """
    sql = """
    WITH last_tof AS (
        SELECT sd.sensor_id, MAX(sd.sensor_time) AS last_time
        FROM sensor_data sd GROUP BY sd.sensor_id
    ),
    last_with AS (
        SELECT w.sensor_id, MAX(w.sensor_time) AS last_time
        FROM sensor_with w GROUP BY w.sensor_id
    ),
    last_cam AS (
        SELECT c.sensor_id, MAX(c.sensor_time) AS last_time
        FROM sensorcam c GROUP BY c.sensor_id
    ),
    last_all AS (
        SELECT sensor_id, last_time FROM last_tof
        UNION ALL
        SELECT sensor_id, last_time FROM last_with
        UNION ALL
        SELECT sensor_id, last_time FROM last_cam
    ),
    last_by_sensor AS (
        SELECT sensor_id, MAX(last_time) AS last_time
        FROM last_all
        GROUP BY sensor_id
    )
    SELECT b.sensor_id::text AS sensor_id,
           b.user_id::text AS user_id,
           b.sensor_type::text AS sensor_type,
           COALESCE(b.location::text, '') AS location,
           COALESCE(u.name::text, '') AS name,
           lbs.last_time
    FROM sensor_binding b
    JOIN last_by_sensor lbs ON lbs.sensor_id = b.sensor_id
    LEFT JOIN users u ON b.user_id = u.user_id
    ORDER BY b.user_id, b.sensor_type, b.location;
    """
    df = fetch_df(sql)
    if df.empty:
        return df
    now = pd.Timestamp.now(tz="Asia/Seoul").to_pydatetime().replace(tzinfo=None)
    df["sensor_time"] = pd.to_datetime(df["last_time"])
    df["delay_minutes"] = (now - df["sensor_time"]).dt.total_seconds() / 60.0
    df["delay_minutes"] = df["delay_minutes"].fillna(float('inf'))
    df["is_ok"] = df["delay_minutes"] <= float(threshold_minutes)
    # labels
    def label_user(uid, nm):
        nm = (nm or "").strip()
        return f"{uid} ({nm})" if nm else str(uid)
    df["user_label"]   = df.apply(lambda r: label_user(r["user_id"], r.get("name")), axis=1)
    df["sensor_label"] = df["sensor_type"] + " (" + df["sensor_id"].astype(str) + ")"
    df["display"]      = df["user_label"] + " " + df["sensor_label"]
    return df

# ----------------------------------------------------------------------------
# [DASH APP]
# ----------------------------------------------------------------------------
external_stylesheets = [dbc.themes.BOOTSTRAP, "https://codepen.io/chriddyp/pen/bWLwgP.css"]
app = Dash(__name__, external_stylesheets=external_stylesheets)
app.config.suppress_callback_exceptions = True
app.title = "ESM DASHBOARD"

# Theme
dark_bg     = "#f5f7fa"
light_bg    = "#ffffff"
accent      = "#2563eb"
text_color  = "#1e3a8a"
line_color  = "#3b82f6"


def _compact_picker(picker_id):
    today = datetime.now().date()
    return html.Div([
        dcc.DatePickerRange(
            id=picker_id,
            start_date=today,
            end_date=today + timedelta(days=1),

            minimum_nights=0,
            display_format="YYYY-MM-DD",
            persistence=True,
            persistence_type="local",
            style={
                "display": "inline-block",
                "fontSize": "11px",
                "backgroundColor": "#fff",
                "color": "#000",
                "border": "1px solid #ddd",
                "borderRadius": "6px",
                "padding": "2px 4px",
            },
        ),
    ], style={"display": "inline-flex", "alignItems": "center", "gap": "4px"})

app.layout = html.Div(style={"backgroundColor": dark_bg, "color": text_color, "padding": "20px"}, children=[
    html.H1("ESM & Sensor DASHBOARD", style={"color": accent, "backgroundColor": dark_bg, "padding": "10px", "borderRadius": "10px"}),

    # ======= 상단: 응답 여부/현황/이상 감지 =======
    html.Div([
        html.Div([
            html.H4("사용자 응답 여부", style={"marginBottom": "0px", "color": accent}),
            html.Small("사용자 및 날짜 별로 응답 여부를 확인하실 수 있습니다.", style={"color": "#777", "fontSize": "10px"}),

            html.Div([
                html.Div([
                    html.Div([
                        html.Div([
                            html.Label("사용자 선택"),
                            dcc.Dropdown(
                                id="user-dropdown",
                                options=[],  # populated by callback
                                multi=True,
                                value=[], # default로 '전체 보기'
                                placeholder="사용자를 선택하세요",
                                style={"backgroundColor": "#ffffff", "color": "#000000", "minWidth": "130px"}
                            )
                        ], style={"marginRight": "10px"}),

                        html.Div([
                            html.Label("응답 날짜 선택"),
                            dcc.DatePickerRange(
                                id="response-stats-date-picker",
                                start_date=datetime.now().date() - timedelta(days=7),
                                end_date=datetime.now().date(),
                                persistence=True,
                                persistence_type="local",
                                style={"backgroundColor": "#ffffff", "color": "#000000", "minWidth": "110px"}
                            ),
                        ], style={"marginRight": "10px"}),

                        html.Div([
                            html.Label("응답 표출 조절"),
                            dcc.Input(
                                id="response-count-input",
                                type="number",
                                value=6,
                                min=1,
                                step=1,
                                debounce=False, # 실시간 적용할 시 False
                                inputMode="numeric", # 🎯 모바일 대응
                                style={"width": "80px", "marginLeft": "auto"}
                            )
                        ]),

                        html.Div([
                            html.Label("일자별로"),
                            dcc.RadioItems(
                                id="byday-switch",
                                options=[{"label": "OFF", "value": "off"}, {"label": "ON", "value": "on"}],
                                value="off",
                                labelStyle={"display":"inline-block","padding":"4px 10px","border":"1px solid #333","borderRadius":"6px","marginRight":"6px","cursor":"pointer","fontWeight":600},
                                inputStyle={"marginRight":"6px"},
                                style={"display":"inline-block"}
                            )
                        ])
                    ], style={
                        "display": "flex", "flexDirection": "row", "flexWrap": "wrap", "gap": "15px", "marginBottom": "15px"
                    }),
                    html.Div([
                    dash_table.DataTable(
                        id="answer-status-table",
                        columns=[{"name": "사용자 ID", "id": "user_id"}] +
                                [{"name": f"{i+1}", "id": f"{i+1}"} for i in range(20)] +
                                [{"name": "", "id": f"status_{i+1}"} for i in range(20)],
                        hidden_columns=[f"status_{i+1}" for i in range(20)],
                        style_table={"overflowX": "auto", "maxHeight": "300px", "overflowY": "auto", "backgroundColor": light_bg},
                        style_cell={"whiteSpace": "pre-line", "textAlign": "center", "padding": "8px", "color": text_color},
                        style_header={"backgroundColor": accent, "color": "#000000", "fontWeight": "bold"},
                        css=[{"selector": ".dash-spreadsheet-menu", "rule": "display: none"}],
                        style_data_conditional=[
                            # ✅ "*"인 경우 → 초록색 bold
                            *[{"if": {"filter_query": f'{{status_{i+1}}} = "*"', "column_id": f"{i+1}"}, "color": "green", "fontWeight": "bold"} for i in range(20)],
                            # ✅ "X"인 경우 → 빨간색
                            *[{"if": {"filter_query": f'{{status_{i+1}}} = "X"', "column_id": f"{i+1}"}, "color": "red"} for i in range(20)],
                        ]
                    )
                    ], id="byday-matrix-wrap", style={"display":"block"}),
                    html.Div(id="byday-ratio-wrap", style={"display":"none"})    
                ], style={"flex": "1", "width": "80%", "minWidth": "120px", "backgroundColor": light_bg, "borderRadius": "8px"}),
                html.Div(id="response-statistics-display", style={"marginLeft": "20px", "marginTop": "80px"})
            ], style={"display": "flex", "flexDirection": "row", "alignItems": "flex-start"})
        ], style={"width": "55%", "backgroundColor": light_bg, "padding": "15px", "borderRadius": "8px", "maxHeight": "300px", "overflowY": "auto"}),

        html.Div([
            html.H4("ESM 응답자 현황", style={"marginBottom": "0px", "color": accent}),
            html.Small("오늘자 응답 현황 정보로 마우스를 갖다대시면 응답자별 정보를 확인하실 수 있습니다. '일자별로'를 ON으로 바꾸시면 해당일의 응답 여부(O/X만)를 표출합니다.", style={"color": "#777", "fontSize": "10px"}),
            html.H5("응답 중인 사용자", style={"color": accent}),
            html.Ul(id="responding-user-list", style={"listStyleType":"none","paddingLeft":"0","margin":"0"}),
            html.H5("누락자", style={"color": accent}),
            html.Ul(id="missing-user-list", style={"listStyleType":"none","paddingLeft":"0","margin":"0"}),
html.Div([
    dbc.Button("누락자에게 카카오톡 보내기", id="btn-send-kakao", color="primary", size="sm", n_clicks=0),
    dbc.Checklist(options=[{"label":" 자동 발송(매시간)", "value":"auto"}], value=[], id="kakao-auto", switch=True, style={"display":"inline-block","marginLeft":"10px"}),
    html.Small(id="kakao-status", style={"marginLeft":"10px", "color":"#555"})
], style={"marginTop":"8px"})
        ], style={"width": "22%", "backgroundColor": light_bg, "padding": "15px", "borderRadius": "8px", "maxHeight": "300px", "overflowY": "auto"}),

        html.Div([
            html.H4("⚠️ 센서 이상 감지", style={"marginBottom": "0px", "color": accent}),
            html.Small("1분 이상 데이터 미수신시 이상으로 판단합니다.", style={"color": "#777", "fontSize": "10px"}),
            html.Div(id="sensor-abnormal-list")
        ], style={"width": "22%", "backgroundColor": light_bg, "padding": "15px", "borderRadius": "8px", "maxHeight": "300px", "overflowY": "auto"})
    ], style={"display": "flex", "justifyContent": "space-between", "gap": "15px", "marginBottom": "30px"}),

    # ======= 센서 시계열 =======
    html.Div([
        html.H4("센서 시계열 시각화", style={"marginBottom": "0px", "color": accent}),
        html.Small("사용자/센서 위치/날짜별로 시계열 그래프를 확인하실 수 있습니다. TOF: 해당 사물을 사용하고 있는지[센서로부터 떨어진 거리(mm)] / SIT: 해당 물체에 앉아 있는지[Yes/No(binary)] / WITH: 해당 물체에 누워 있는지[사용자의 심박수(BPM)] / CAM: 사용자가 취하고 있는 행동", style={"color": "#777", "fontSize": "10px"}),

        html.Div([
            html.Div([
                html.Label("사용자 선택", style={"marginBottom": "5px"}),
                dcc.Dropdown(
                    id="user-dropdown2",
                    options=[],          # populated by callback
                    multi=True,
                    value=[],
                    placeholder="사용자를 선택하세요",
                    style={"width": "180px", "backgroundColor": "#ffffff", "color": "#000000"}
                )
            ], style={"marginRight": "30px"})], style={"display": "flex", "flexDirection": "row", "marginBottom": "15px"}),

        html.Div(id="sensor-time-series-wrapper")
    ], style={"marginTop": "30px"}),

        dcc.Store(id="loc-memory", storage_type="local"),
    dcc.Interval(id="interval-component", interval=30*1000, n_intervals=0),
dcc.Interval(id="kakao-interval", interval=60*60*1000, n_intervals=0),
dcc.Store(id="kakao-targets")
])

# ----------------------------------------------------------------------------
# [CALLBACKS]
# ----------------------------------------------------------------------------

# Populate user dropdowns lazily
@app.callback(
    Output("user-dropdown", "options"),
    Output("user-dropdown2", "options"),
    Input("interval-component", "n_intervals")
)
def _populate_user_dropdowns(_):
    opts = get_all_user_options()
    return opts, opts

# --- Answer O/X Table -------------------------------------------------------
def build_answer_matrix(start_date, end_date, selected_users, response_count=6) -> pd.DataFrame:
    now = pd.Timestamp.now(tz='Asia/Seoul').replace(tzinfo=None)

    # Alarm logs (file)
    alarm_df = load_alarm_log()
    if alarm_df.empty:
        # minimal matrix
        user_ids = selected_users if (selected_users and "ALL" not in selected_users) else []
        matrix = pd.DataFrame("-", index=user_ids, columns=[str(i+1) for i in range(response_count)])
        for i in range(response_count):
            matrix[f"status_{i+1}"] = "-"
        matrix.reset_index(inplace=True)
        matrix.rename(columns={"index": "user_id"}, inplace=True)
        return matrix

    # Date filter on logs
    if start_date and end_date:
        s = pd.to_datetime(start_date).date()
        e = pd.to_datetime(end_date).date()
        alarm_df = alarm_df[(alarm_df["start_time"].dt.date >= s) & (alarm_df["start_time"].dt.date <= e)]

    # Answer table (DB) — only necessary columns
    sql = """
        SELECT user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL
          AND answered_at >= :start_bound
          AND answered_at <= :end_bound
    """
    # bounds from logs, or from UI
    if not alarm_df.empty:
        min_log = alarm_df["start_time"].min()
        max_log = alarm_df["end_time"].max()
    else:
        # fallback: UI range or today
        min_log = pd.to_datetime(start_date) if start_date else pd.Timestamp.now() - pd.Timedelta(days=1)
        max_log = pd.to_datetime(end_date)   if end_date   else pd.Timestamp.now()

    df_session = fetch_df(sql, {"start_bound": min_log, "end_bound": max_log})
    df_session["answered_at"] = pd.to_datetime(df_session["answered_at"])
    answer_df = df_session[["user_id", "answered_at"]]

    # Users set
    user_ids = ([] if not selected_users else (fetch_df("SELECT DISTINCT user_id::text AS user_id FROM users")['user_id'].tolist() if 'ALL' in selected_users else selected_users))

    # Init matrix
    matrix = pd.DataFrame("-", index=user_ids, columns=[str(i+1) for i in range(response_count)])
    for i in range(response_count):
        matrix[f"status_{i+1}"] = "-"

    alarm_df = alarm_df.sort_values("start_time")

    for uid in user_ids:
        user_alarms = alarm_df[alarm_df["user_id"].astype(str) == str(uid)]
        user_answers = answer_df[answer_df["user_id"].astype(str) == str(uid)]
        slot = 1

        for _, alarm in user_alarms.iterrows():
            if slot > response_count:
                break

            start = pd.to_datetime(alarm["start_time"])
            end   = pd.to_datetime(alarm["end_time"])
            date_str = f"{start.month}/{start.day}"

            answered = not user_answers[user_answers["answered_at"].between(start, end)].empty

            if answered:
                status = "O"
            elif start <= now <= end:
                status = "*"
            elif now > end:
                status = "X"
            else:
                status = "-"

            matrix.loc[uid, str(slot)] = f"{status}\n{date_str}"
            matrix.loc[uid, f"status_{slot}"] = status
            slot += 1

    matrix.reset_index(inplace=True)
    matrix.rename(columns={"index": "user_id"}, inplace=True)

    # label map
    label_map = get_user_map_for(user_ids)
    matrix["user_id"] = matrix["user_id"].map(lambda u: label_map.get(u, u))

    return matrix

@app.callback(
    Output("answer-status-table", "data"),
    Output("answer-status-table", "columns"),
    Input("response-stats-date-picker", "start_date"),
    Input("response-stats-date-picker", "end_date"),
    Input("user-dropdown", "value"),
    Input("response-count-input", "value"),
    Input("interval-component", "n_intervals"),
)
def update_answer_status_table(start_date, end_date, selected_users, response_count, _):
    matrix = build_answer_matrix(start_date, end_date, selected_users, response_count or 6)
    columns = [{"name": col, "id": col} for col in matrix.columns]
    data = matrix.to_dict("records")
    return data, columns

# --- Response Statistics (per period) ---------------------------------------
@app.callback(
    Output("response-statistics-display", "children"),
    Input("user-dropdown", "value"),
    Input("response-stats-date-picker", "start_date"),
    Input("response-stats-date-picker", "end_date")
)

def update_response_statistics(user_list, start_date, end_date):
    if not user_list:
        return ""
    if not start_date or not end_date:
        return "응답 기간을 설정해주세요."


    s_date = pd.to_datetime(start_date).date()
    e_date = pd.to_datetime(end_date).date()

    # Logs
    alarm_df = load_alarm_log()
    if alarm_df.empty:
        return "해당 기간에 로그가 없습니다."
    alarm_df["date"] = alarm_df["start_time"].dt.date
    alarm_df = alarm_df[(alarm_df["date"] >= s_date) & (alarm_df["date"] <= e_date)]
    if user_list and 'ALL' not in (user_list or []):
        alarm_df = alarm_df[alarm_df["user_id"].astype(str).isin([str(u) for u in user_list])]

    if alarm_df.empty:
        return "해당 기간에 로그가 없습니다."

    # Answer DB within the log bounds
    min_ts = alarm_df["start_time"].min()
    max_ts = alarm_df["end_time"].max()
    sql = """
        SELECT user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL
          AND answered_at >= :s AND answered_at <= :e
    """
    df_session = fetch_df(sql, {"s": min_ts, "e": max_ts})
    df_session["answered_at"] = pd.to_datetime(df_session["answered_at"])
    if user_list and 'ALL' not in (user_list or []):
        df_session = df_session[df_session["user_id"].isin([str(u) for u in user_list])]

    # per-user rate
    label_map = get_user_map_for(sorted(alarm_df["user_id"].astype(str).unique().tolist()))
    results = []
    for uid in sorted(alarm_df["user_id"].astype(str).unique()):
        total_logs = alarm_df[alarm_df["user_id"].astype(str) == uid]
        total = len(total_logs)
        answered = 0
        for _, row in total_logs.iterrows():
            s, e = pd.to_datetime(row["start_time"]), pd.to_datetime(row["end_time"])
            if not df_session[(df_session["user_id"] == uid) & df_session["answered_at"].between(s, e)].empty:
                answered += 1
        rate = (answered / total) * 100 if total else 0
        results.append(html.Div(f"{label_map.get(uid, uid)}: {rate:.1f}%"))

    # overall avg
    total_answered = 0
    for _, row in alarm_df.iterrows():
        uid = str(row["user_id"])
        s, e = pd.to_datetime(row["start_time"]), pd.to_datetime(row["end_time"])
        if not df_session[(df_session["user_id"] == uid) & df_session["answered_at"].between(s, e)].empty:
            total_answered += 1
    avg = (total_answered / len(alarm_df)) * 100 if len(alarm_df) else 0
    results.append(html.Hr(style={"borderTop": "1px solid #ccc", "margin": "8px 0"}))
    results.append(html.Div(f"📊 전체 평균 응답률: {avg:.1f}%"))

    # daily best/worst
    def _day_rate(day_df: pd.DataFrame) -> float:
        cnt = 0
        for _, r in day_df.iterrows():
            uid = str(r["user_id"])
            s, e = pd.to_datetime(r["start_time"]), pd.to_datetime(r["end_time"])
            if not df_session[(df_session["user_id"] == uid) & df_session["answered_at"].between(s, e)].empty:
                cnt += 1
        return (cnt / len(day_df)) * 100 if len(day_df) else 0.0

    daily = alarm_df.groupby("date", group_keys=False).apply(_day_rate)
    if not daily.empty:
        results.append(html.Div(f"📈 일일 최고 응답률: {daily.max():.1f}% on {daily.idxmax()}"))
        results.append(html.Div(f"📉 일일 최저 응답률: {daily.min():.1f}% on {daily.idxmin()}"))
    return results


# --- Responding/Missing lists (today) ---------------------------------------
@app.callback(
    Output("responding-user-list", "children"),
    Output("missing-user-list", "children"),
    Input("interval-component", "n_intervals"),
    Input("byday-switch", "value"),
    Input("byday-ratio-table", "active_cell"),
    Input("byday-ratio-table", "selected_cells"),
    State("byday-ratio-table", "data"),
)
def update_user_lists(_, byday_mode, active_cell, selected_cells, byday_data):
    # ---- Selection path: when '일자별로' is ON and cells are selected ----
    cells = (selected_cells or [])[:]
    if (not cells) and active_cell:
        cells = [active_cell]
    if byday_mode == "on" and byday_data and cells:
        sel_pairs = set()
        for c in cells:
            try:
                r = int(c.get("row", -1)); col = c.get("column_id")
            except Exception:
                r, col = -1, None
            if r is None or r < 0 or not col or col in ("user_id", "uid"):
                continue
            try:
                uid = str(byday_data[r].get("uid"))
                day = pd.to_datetime(col).date()
            except Exception:
                continue
            sel_pairs.add((uid, day))

        if sel_pairs:
            alarm_df = load_alarm_log()
            if alarm_df.empty:
                return [], []
            alarm_df["date"] = pd.to_datetime(alarm_df["start_time"]).dt.date
            users = sorted({u for (u, _) in sel_pairs})
            days = sorted({d for (_, d) in sel_pairs})
            alarm_df = alarm_df[alarm_df["user_id"].astype(str).isin(users) & alarm_df["date"].isin(days)]
            if alarm_df.empty:
                return [], []

            now = pd.Timestamp.now(tz='Asia/Seoul').replace(tzinfo=None)
            # Only past windows (exclude * and -)
            alarm_df = alarm_df[pd.to_datetime(alarm_df["end_time"]) <= now]
            if alarm_df.empty:
                return [], []

            min_ts = alarm_df["start_time"].min(); max_ts = alarm_df["end_time"].max()
            df_session = fetch_df(
                """
                SELECT user_id::text AS user_id, answered_at
                FROM survey_session
                WHERE answered_at IS NOT NULL AND answered_at BETWEEN :s AND :e
                """, {"s": min_ts, "e": max_ts}
            )
            df_session["answered_at"] = pd.to_datetime(df_session["answered_at"])
            label_map = get_user_map_for(users)

            def _li(label, s, e):
                time_str = f"{pd.to_datetime(s).strftime('%H:%M')}~{pd.to_datetime(e).strftime('%H:%M')}"
                return html.Li(html.Span(f"{label} ({time_str})", style={"color": text_color}), style={"marginBottom": "6px"})

            resp_items, miss_items = [], []
            for uid, day in sel_pairs:
                day_logs = alarm_df[(alarm_df["user_id"].astype(str) == uid) & (alarm_df["date"] == day)].sort_values("start_time")
                for _, a in day_logs.iterrows():
                    s = pd.to_datetime(a["start_time"]); e = pd.to_datetime(a["end_time"])
                    answered = not df_session[(df_session["user_id"] == uid) & df_session["answered_at"].between(s, e)].empty
                    label = label_map.get(uid, uid)
                    if answered:
                        resp_items.append(_li(label, s, e))
                    else:
                        miss_items.append(_li(label, s, e))
            return resp_items, miss_items
    # ---- Default path (today behavior) ----
    now = pd.Timestamp.now(tz='Asia/Seoul').replace(tzinfo=None)
    today = pd.Timestamp.now(tz='Asia/Seoul').normalize()

    alarm_df = load_alarm_log()
    if alarm_df.empty:
        return [], []

    # today's logs only
    alarm_df_today = alarm_df[pd.to_datetime(alarm_df["start_time"], utc=True).dt.tz_convert("Asia/Seoul").dt.normalize() == today]
    if alarm_df_today.empty:
        return [], []

    # Fetch recent answered_at only around today's window
    min_ts = alarm_df_today["start_time"].min()
    max_ts = alarm_df_today["end_time"].max()

    # --- Answer tables ---
    # (A) Window-bounded answers for in-window checks (unchanged)
    sql = """
        SELECT user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL AND answered_at BETWEEN :s AND :e
    """
    df_session = fetch_df(sql, {"s": min_ts, "e": max_ts})
    df_session["answered_at"] = pd.to_datetime(df_session["answered_at"])

    # (B) Global latest answer per user for tooltip "최근 응답"
    sql_latest = """
        SELECT DISTINCT ON (user_id) user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL
        ORDER BY user_id, answered_at DESC
    """
    df_last = fetch_df(sql_latest)
    df_last["answered_at"] = pd.to_datetime(df_last["answered_at"])
    last_ans_map = {str(r["user_id"]): r["answered_at"] for _, r in df_last.iterrows()}

    # (C) User info map from users table (툴팁용)
    try:
        df_users_tip = fetch_df("SELECT user_id::text AS user_id, COALESCE(info, '') AS info FROM users")
    except Exception:
        df_users_tip = fetch_df("SELECT user_id::text AS user_id FROM users")
        df_users_tip["info"] = ""
    info_map = {str(r["user_id"]): (r.get("info") or "") for _, r in df_users_tip.iterrows()}


    # user labels
    label_map = get_user_map_for(sorted(alarm_df_today["user_id"].astype(str).unique().tolist()))

    # Responding
    resp_items = []
    for _, alarm in alarm_df_today.iterrows():
        uid = str(alarm["user_id"])
        start = pd.to_datetime(alarm["start_time"])
        end   = pd.to_datetime(alarm["end_time"])
        if start <= now <= end:
            label = label_map.get(uid, uid)
            time_str = f"{start.strftime('%H:%M')}~{end.strftime('%H:%M')}"
            user_answers = df_session[df_session["user_id"] == uid]
            last_ans = user_answers["answered_at"].max() if not user_answers.empty else None
            last_str = pd.to_datetime(last_ans_map.get(uid)).strftime("%Y-%m-%d %H:%M:%S") if last_ans_map.get(uid) is not None else "-"
            info_str = info_map.get(uid, "-")

            target_id = f"esm-resp-{uid}-{int(pd.Timestamp(start).timestamp())}"
            resp_items.append(
                html.Li([
                    html.Span(f"{label} ({time_str})", id=target_id, style={"color": text_color, "cursor": "pointer", "textDecoration": "none"}),
                    dbc.Tooltip(html.Div([
                        html.Div(f"info: {info_str}"),
                        html.Div(f"최근 응답: {last_str}"),
                    ]), target=target_id, placement="right", autohide=True, delay={"show": 80, "hide": 150})
                ], style={"marginBottom": "6px"})
            )

    # Missing
    miss_items = []
    for _, alarm in alarm_df_today.iterrows():
        uid = str(alarm["user_id"])
        start = pd.to_datetime(alarm["start_time"])
        end   = pd.to_datetime(alarm["end_time"])
        if now > end:
            answered = df_session[(df_session["user_id"] == uid) & df_session["answered_at"].between(start, end)]
            if answered.empty:
                label = label_map.get(uid, uid)
                time_str = f"{start.strftime('%H:%M')}~{end.strftime('%H:%M')}"
                # For tooltip: last response & info (even if missing)
                last_str = pd.to_datetime(last_ans_map.get(uid)).strftime("%Y-%m-%d %H:%M:%S") if last_ans_map.get(uid) is not None else "-"
                info_str = info_map.get(uid, "-")
                target_id = f"esm-miss-{uid}-{int(pd.Timestamp(start).timestamp())}"
                miss_items.append(
                    html.Li([
                        html.Span(f"{label} ({time_str})", id=target_id, style={"color": text_color, "cursor": "pointer", "textDecoration": "none"}),
                        dbc.Tooltip(html.Div([
                            html.Div(f"info: {info_str}"),
                            html.Div(f"최근 응답: {last_str}"),
                        ]), target=target_id, placement="right", autohide=True, delay={"show": 80, "hide": 150})
                    ], style={"marginBottom": "6px"})
                )

    return resp_items, miss_items


def _collect_missing_for_message(byday_mode, active_cell, selected_cells, byday_data):
    """Return list[{"label": str, "start": pd.Timestamp, "end": pd.Timestamp}] for current context."""
    import pandas as _pd
    now = _pd.Timestamp.now(tz='Asia/Seoul').replace(tzinfo=None)
    alarms = load_alarm_log()
    if alarms.empty:
        return []
    # Helper to append one slot if missing
    def _append_slots(df_logs, users_filter=None, day_filter=None):
        out = []
        df = df_logs.copy()
        if users_filter is not None:
            df = df[df["user_id"].astype(str).isin(users_filter)]
        if day_filter is not None:
            df["date"] = _pd.to_datetime(df["start_time"]).dt.date
            df = df[df["date"].isin(day_filter)]
        if df.empty:
            return out
        df = df[_pd.to_datetime(df["end_time"]) <= now]  # only past windows
        if df.empty:
            return out
        min_ts = df["start_time"].min(); max_ts = df["end_time"].max()
        df_sess = fetch_df(
            "SELECT user_id::text AS user_id, answered_at FROM survey_session "
            "WHERE answered_at IS NOT NULL AND answered_at BETWEEN :s AND :e",
            {"s": min_ts, "e": max_ts}
        )
        df_sess["answered_at"] = _pd.to_datetime(df_sess["answered_at"])
        label_map = get_user_map_for(sorted(df["user_id"].astype(str).unique().tolist()))
        for _, a in df.sort_values("start_time").iterrows():
            uid = str(a["user_id"])
            s = _pd.to_datetime(a["start_time"]); e = _pd.to_datetime(a["end_time"])
            answered = not df_sess[(df_sess["user_id"] == uid) & df_sess["answered_at"].between(s, e)].empty
            if not answered:
                out.append({"uid": uid, "label": label_map.get(uid, uid), "start": s, "end": e})
                # out.append({"label": label_map.get(uid, uid), "start": s, "end": e})
        return out

    # If byday ON and there is selection -> filter by the selected cells
    cells = (selected_cells or [])[:]
    if (not cells) and active_cell:
        cells = [active_cell]
    if byday_mode == "on" and byday_data and cells:
        sel_pairs = set()
        for c in cells:
            try:
                r = int(c.get("row", -1)); col = c.get("column_id")
            except Exception:
                r, col = -1, None
            if r is None or r < 0 or not col or col in ("user_id", "uid"):
                continue
            try:
                uid = str(byday_data[r].get("uid"))
                day = _pd.to_datetime(col).date()
            except Exception:
                continue
            sel_pairs.add((uid, day))
        if not sel_pairs:
            return []
        users = sorted({u for (u, _) in sel_pairs})
        days  = sorted({d for (_, d) in sel_pairs})
        return _append_slots(alarms, users_filter=users, day_filter=days)

    # else: default to today's missing
    today = _pd.Timestamp.now(tz='Asia/Seoul').normalize()
    alarms_today = alarms[_pd.to_datetime(alarms["start_time"], utc=True).dt.tz_convert("Asia/Seoul").dt.normalize() == today]
    if alarms_today.empty:
        return []
    return _append_slots(alarms_today)


# def _compute_missing_targets(byday_mode, active_cell, selected_cells, byday_data): 
#     """ 누락 슬롯을 [{uid,label,start,end,kakao_uuid}]로 반환. - kakao_uuid는 JSON 매핑에서 조회, 없으면 None. """ 
#     slots = _collect_missing_for_message(byday_mode, active_cell, selected_cells, byday_data) 
#     if not slots: return [] 
#     # 이 컨텍스트에 등장하는 사용자 목록 추출 # label_map은 이미 적용된 label이 들어있음(필요시 유지) 
#     # # uid는 slots에 없으므로, start/end 조회용으로 Alarm 로그에서 다시 가져오기 대신 
#     # # 여기서는 'label'에서 uid를 역추정하지 않고 users 테이블에서 전부 가져오지 않도록, 
#     # # 알람 로그에서 유저만 모아온다. 
#     alarms = load_alarm_log() 
#     if alarms.empty: user_ids = [] 
#     else: 
#         # slots의 label이 "123 (홍길동)" 형식일 수 있으므로, 알람 로그에서 시간 매칭으로 uid 집합 추출 
#         # # 단순화: 오늘/선택된 구간의 user_id 전부 
#         user_ids = sorted(alarms["user_id"].astype(str).unique().tolist()) 
#         uuid_map = get_kakao_uuid_map(user_ids) 
#         out = [] 
#         # slots에는 uid가 없으므로 label만으로는 uuid 매칭이 어려움 → 라벨맵 없이 uid를 함께 담고 싶다면 
#         # # _collect_missing_for_message에서 uid도 담도록 개선하는 게 최선. 
#         # # 빠른 해결: label 텍스트에서 uid가 앞에 오는 기존 label_map 규칙("uid (name)")을 활용. 
#         for s in slots: label = s.get("label", "") 
#         # uid 추출: '123 (홍길동)' 또는 '123' 
#         uid_guess = label.split(" ", 1)[0].strip() 
#         kakao_uuid = uuid_map.get(uid_guess) 
#         out.append({ "uid": uid_guess, "label": label, "start": s.get("start"), "end": s.get("end"), "kakao_uuid": kakao_uuid }) 
#         return out
def _compute_missing_targets(byday_mode, active_cell, selected_cells, byday_data):
    """
    누락 슬롯을 [{uid,label,start,end,kakao_uuid}]로 반환.
    kakao_uuid는 JSON 매핑에서 조회(없으면 None).
    """
    slots = _collect_missing_for_message(byday_mode, active_cell, selected_cells, byday_data)
    if not slots:
        return []

    # slots 안에 uid가 들어있다고 가정(위 함수에서 추가)
    user_ids = sorted({str(s.get("uid")) for s in slots if s.get("uid")})
    uuid_map = get_kakao_uuid_map(user_ids)

    out = []
    for s in slots:
        uid = str(s.get("uid") or "")
        out.append({
            "uid": uid,
            "label": s.get("label"),
            "start": s.get("start"),
            "end": s.get("end"),
            "kakao_uuid": uuid_map.get(uid)
        })
    return out

        
@app.callback(
    Output("kakao-open-url", "data"),
    Input("interval-component", "n_intervals"),
    Input("byday-switch", "value"),
    Input("byday-ratio-table", "active_cell"),
    Input("byday-ratio-table", "selected_cells"),
    State("byday-ratio-table", "data"),
)
def _make_kakao_open_url(_, byday_mode, active_cell, selected_cells, byday_data):
    slots = _collect_missing_for_message(byday_mode, active_cell, selected_cells, byday_data)
    if not slots:
        return "/kakao/index"
    lines = ["[ESM 안내] 응답 누락 슬롯"]
    for t in slots:
        lines.append(f"- {t['label']} {t['start'].strftime('%H:%M')}~{t['end'].strftime('%H:%M')}")
    msg = "\n".join(lines)
    return "/kakao/index?text=" + quote(msg)

# --- Abnormal sensors (every 30s) -------------------------------------------
@app.callback(
    Output("sensor-abnormal-list", "children"),
    Input("interval-component", "n_intervals")
)
def update_abnormal_sensor_list(_):
    # Query status for bound sensors that have at least one datapoint
    df_all = query_sensor_status_all(threshold_minutes=1.0)
    if df_all.empty:
        return [html.P("센서 바인딩 정보가 없습니다.", style={"color": "gray"})]

    # 정상(녹색) 센서가 먼저 표출되도록 설정. 이상(적색) 센서가 먼저 뜨게 하려면 [True, True, True, True]로 정렬
    df_all = df_all.sort_values(["is_ok", "user_label", "sensor_type", "location"], ascending=[False, True, True, True])

    items = []
    for _, r in df_all.iterrows():
        target_id = f"abn-{r['sensor_type']}-{r['sensor_id']}"
        last_ts = pd.to_datetime(r["sensor_time"]) if pd.notna(r.get("sensor_time")) else None
        last_str = last_ts.strftime("%Y-%m-%d %H:%M:%S") if last_ts is not None else "-"
        delay_min = f"{float(r['delay_minutes']):.1f}" if pd.notna(r.get("delay_minutes")) else "-"
        loc_str = r.get("location") or "-"
        color = "green" if r.get("is_ok") else "red"
        label_text = ("🟢 " if r.get("is_ok") else "") + r["display"]
        items.append(
            html.Li([
                html.Span(label_text, id=target_id, style={"color": color, "cursor": "pointer", "textDecoration": "none"}),
                dbc.Tooltip(html.Div([
                    html.Div(f"위치: {loc_str}"),
                    html.Div(f"마지막 수신: {last_str}"),
                    html.Div(f"지연: {delay_min}분"),
                ]), target=target_id, placement="right", autohide=True, delay={"show": 80, "hide": 150})
            ], style={"marginBottom": "6px"})
        )
    return [html.Ul(items, style={"listStyleType":"none","paddingLeft":"0","margin":"0"})]
# --- Sensor time-series grid (per-user rows) --------------------------------
@app.callback(
    Output("sensor-time-series-wrapper", "children"),
    Input("user-dropdown2", "value"),
    Input("interval-component", "n_intervals"),
)

def update_user_sensor_rows(selected_users, _):
    # If none selected → show nothing
    if not selected_users:
        return []
    # If ALL selected → list all users (light query)
    if "ALL" in selected_users:
        users = fetch_df("SELECT user_id::text AS user_id FROM users ORDER BY user_id")["user_id"].tolist()
    else:
        users = selected_users

    user_map = get_user_map_for(users)

    rows = []
    for uid in users:
        rows.append(
            html.Div([
                html.Div([
                    html.H4(user_map.get(uid, uid), style={"margin": 0}),
                    html.Div([
                        html.Div([html.Small("TOF 위치", style={"marginRight":"6px"}),
                                  dcc.Dropdown(id={"type":"loc-tof","user": uid},
                                               options=[], value=None, clearable=False, placeholder="선택",
                                               persistence=True, persistence_type="local",
                                               style={"width":"150px","backgroundColor":"#ffffff","color":"#000000","fontSize":"12px"})],
                                 style={"display":"flex","alignItems":"center","gap":"4px"}),

                        html.Div([html.Small("SIT 위치", style={"marginRight":"6px"}),
                                  dcc.Dropdown(id={"type":"loc-sit","user": uid},
                                               options=[], value=None, clearable=False, placeholder="선택",
                                               persistence=True, persistence_type="local",
                                               style={"width":"150px","backgroundColor":"#ffffff","color":"#000000","fontSize":"12px"})],
                                 style={"display":"flex","alignItems":"center","gap":"4px"}),

                        html.Div([html.Small("WITH 위치", style={"marginRight":"6px"}),
                                  dcc.Dropdown(id={"type":"loc-with","user": uid},
                                               options=[], value=None, clearable=False, placeholder="선택",
                                               persistence=True, persistence_type="local",
                                               style={"width":"150px","backgroundColor":"#ffffff","color":"#000000","fontSize":"12px"})],
                                 style={"display":"flex","alignItems":"center","gap":"4px"}),

                        html.Div([html.Small("CAM 위치", style={"marginRight":"6px"}),
                                  dcc.Dropdown(id={"type":"loc-cam","user": uid},
                                               options=[], value=None, clearable=False, placeholder="선택",
                                               persistence=True, persistence_type="local",
                                               style={"width":"150px","backgroundColor":"#ffffff","color":"#000000","fontSize":"12px"})],
                                 style={"display":"flex","alignItems":"center","gap":"4px"}),
                    ], style={"display":"flex","gap":"10px"}),
                ], style={"display":"flex","justifyContent":"space-between","alignItems":"center","marginBottom":"8px"}),

                html.Div([
                    # TOF
                    html.Div([
                        html.Div([html.Strong("TOF 센서 (사물)"),
                                  _compact_picker({"type": "range-tof", "user": uid})],
                                 style={"display":"flex","alignItems":"center","justifyContent":"space-between","gap":"8px","marginBottom":"4px"}),
                        dcc.Graph(id={"type": "fig-tof", "user": uid}, style={"height": "260px"}),
                    ], style={"backgroundColor": light_bg, "padding": "6px", "borderRadius": "8px"}),

                    # SIT
                    html.Div([
                        html.Div([html.Strong("SIT 센서 (착좌)"),
                                  _compact_picker({"type": "range-sit", "user": uid})],
                                 style={"display":"flex","alignItems":"center","justifyContent":"space-between","gap":"8px","marginBottom":"4px"}),
                        dcc.Graph(id={"type": "fig-sit", "user": uid}, style={"height": "260px"}),
                    ], style={"backgroundColor": light_bg, "padding": "6px", "borderRadius": "8px"}),

                    # WITH
                    html.Div([
                        html.Div([html.Strong("WITH 센서 (와상)"),
                                  _compact_picker({"type": "range-with", "user": uid})],
                                 style={"display":"flex","alignItems":"center","justifyContent":"space-between","gap":"8px","marginBottom":"4px"}),
                        dcc.Graph(id={"type": "fig-with", "user": uid}, style={"height": "260px"}),
                    ], style={"backgroundColor": light_bg, "padding": "6px", "borderRadius": "8px"}),

                    # CAM
                    html.Div([
                        html.Div([html.Strong("CAM (행동 추적)")],
                                 style={"display":"flex","alignItems":"center","justifyContent":"space-between","gap":"8px","marginBottom":"4px"}),
                        html.Div(id={"type": "cam-box", "user": uid}, style={"height": "260px"}),
                    ], style={"backgroundColor": light_bg, "padding": "6px", "borderRadius": "8px"}),
                ], style={"display":"grid","gridTemplateColumns":"repeat(4, 1fr)","gap":"10px"}),
            ], style={"marginBottom": "30px"})
        )
    return rows

# Per-user location dropdown options/values — from binding table only
def _pick_value(candidates, current):
    return current if current in candidates else (candidates[0] if candidates else None)


def _pick_with_memory(candidates, current, memory, user, sensor_key):
    """Prefer: current -> stored(user,sensor) -> first option."""
    try:
        mem_val = (memory or {}).get(str(user), {}).get(sensor_key)
    except Exception:
        mem_val = None
    pref = current or mem_val
    if pref in candidates:
        return pref
    return candidates[0] if candidates else None

@app.callback(
    Output({"type":"loc-tof","user": MATCH}, "options"),
    Output({"type":"loc-tof","user": MATCH}, "value"),
    Input({"type":"loc-tof","user": MATCH}, "id"),
    State({"type":"loc-tof","user": MATCH}, "value"),
    State("loc-memory", "data"),
)
def _opts_loc_tof(comp_id, cur, memory):
    uid = comp_id.get("user")
    locs = get_locations("TOF", uid)
    return ([{"label": l, "value": l} for l in locs], _pick_with_memory(locs, cur, memory, uid, "TOF"))

@app.callback(
    Output({"type":"loc-sit","user": MATCH}, "options"),
    Output({"type":"loc-sit","user": MATCH}, "value"),
    Input({"type":"loc-sit","user": MATCH}, "id"),
    State({"type":"loc-sit","user": MATCH}, "value"),
    State("loc-memory", "data"),
)
def _opts_loc_sit(comp_id, cur, memory):
    uid = comp_id.get("user")
    locs = get_locations("SIT", uid)
    return ([{"label": l, "value": l} for l in locs], _pick_with_memory(locs, cur, memory, uid, "SIT"))

@app.callback(
    Output({"type":"loc-with","user": MATCH}, "options"),
    Output({"type":"loc-with","user": MATCH}, "value"),
    Input({"type":"loc-with","user": MATCH}, "id"),
    State({"type":"loc-with","user": MATCH}, "value"),
    State("loc-memory", "data"),
)
def _opts_loc_with(comp_id, cur, memory):
    uid = comp_id.get("user")
    locs = get_locations("WITH", uid)
    return ([{"label": l, "value": l} for l in locs], _pick_with_memory(locs, cur, memory, uid, "WITH"))

@app.callback(
    Output({"type":"loc-cam","user": MATCH}, "options"),
    Output({"type":"loc-cam","user": MATCH}, "value"),
    Input({"type":"loc-cam","user": MATCH}, "id"),
    State({"type":"loc-cam","user": MATCH}, "value"),
    State("loc-memory", "data"),
)
def _opts_loc_cam(comp_id, cur, memory):
    uid = comp_id.get("user")
    locs = get_locations("CAM", uid)
    return ([{"label": l, "value": l} for l in locs], _pick_with_memory(locs, cur, memory, uid, "CAM"))

# --- Time-series figures (QUERY-ON-DEMAND) ----------------------------------
def _tof_df(user_id: str, location: str | None, start, end) -> pd.DataFrame:
    if not start or not end or not user_id:
        return pd.DataFrame(columns=["sensor_time", "value", "location"])
    sql = """
        SELECT sd.sensor_time, sd.value, b.location
        FROM sensor_data sd
        JOIN sensor_binding b ON b.sensor_id = sd.sensor_id
        WHERE b.sensor_type = 'TOF'
          AND b.user_id::text = :uid
          AND sd.sensor_time BETWEEN :s AND :e
          {loc_clause}
        ORDER BY sd.sensor_time
    """
    loc_clause = "AND b.location = :loc" if location else ""
    sql = sql.format(loc_clause=loc_clause)
    params = {"uid": str(user_id), "s": pd.to_datetime(start), "e": pd.to_datetime(end)}
    if location:
        params["loc"] = location
    return fetch_df(sql, params)

def _sit_df(user_id: str, location: str | None, start, end) -> pd.DataFrame:
    if not start or not end or not user_id:
        return pd.DataFrame(columns=["sensor_time", "value", "location"])
    sql = """
        SELECT sd.sensor_time, sd.value, b.location
        FROM sensor_data sd
        JOIN sensor_binding b ON b.sensor_id = sd.sensor_id
        WHERE b.sensor_type = 'SIT'
          AND b.user_id::text = :uid
          AND sd.sensor_time BETWEEN :s AND :e
          {loc_clause}
        ORDER BY sd.sensor_time
    """
    loc_clause = "AND b.location = :loc" if location else ""
    sql = sql.format(loc_clause=loc_clause)
    params = {"uid": str(user_id), "s": pd.to_datetime(start), "e": pd.to_datetime(end)}
    if location:
        params["loc"] = location
    return fetch_df(sql, params)

def _with_df(user_id: str, location: str | None, start, end) -> pd.DataFrame:
    if not start or not end or not user_id:
        return pd.DataFrame(columns=["sensor_time", "hr", "location"])
    sql = """
        SELECT w.sensor_time, w.hr, b.location
        FROM sensor_with w
        JOIN sensor_binding b ON b.sensor_id = w.sensor_id
        WHERE b.sensor_type = 'WITH'
          AND b.user_id::text = :uid
          AND w.sensor_time BETWEEN :s AND :e
          {loc_clause}
        ORDER BY w.sensor_time
    """
    loc_clause = "AND b.location = :loc" if location else ""
    sql = sql.format(loc_clause=loc_clause)
    params = {"uid": str(user_id), "s": pd.to_datetime(start), "e": pd.to_datetime(end)}
    if location:
        params["loc"] = location
    return fetch_df(sql, params)

def _cam_latest_row(user_id: str, location: str | None) -> pd.DataFrame:
    if not user_id:
        return pd.DataFrame(columns=["sensor_time","state_label","state_confidence","center_depth_m","location"])
    sql = """
        SELECT c.sensor_time, c.state_label, c.state_confidence, c.center_depth_m, b.location
        FROM sensorcam c
        JOIN sensor_binding b ON b.sensor_id = c.sensor_id
        WHERE b.sensor_type = 'CAM'
          AND b.user_id::text = :uid
          {loc_clause}
        ORDER BY c.sensor_time DESC
        LIMIT 1
    """
    loc_clause = "AND b.location = :loc" if location else ""
    sql = sql.format(loc_clause=loc_clause)
    params = {"uid": str(user_id)}
    if location:
        params["loc"] = location
    return fetch_df(sql, params)

def _make_line(df: pd.DataFrame, ycol: str, title: str, y_label: str):
    if df.empty:
        return px.line(title=f"{title} (데이터 없음)")
    fig = px.line(df.sort_values("sensor_time"), x="sensor_time", y=ycol, color="location",
                  title=title, labels={ycol: y_label, "sensor_time": "시간"})
    fig.update_layout(showlegend=False, paper_bgcolor=dark_bg, plot_bgcolor=light_bg, font_color=text_color,
                      xaxis=dict(rangeslider=dict(visible=True), type="date"))
    return fig

@app.callback(
    Output({"type": "fig-tof", "user": MATCH}, "figure"),
    Input({"type": "range-tof", "user": MATCH}, "start_date"),
    Input({"type": "range-tof", "user": MATCH}, "end_date"),
    Input({"type": "loc-tof", "user": MATCH}, "value"),
    State({"type": "fig-tof", "user": MATCH}, "id"),
)
def _render_tof_fig(start_date, end_date, loc_value, comp_id):
    uid = comp_id.get("user")
    df = _tof_df(uid, loc_value, start_date, end_date)
    return _make_line(df, "value", "TOF 센서 (사물 사용)", "거리")

@app.callback(
    Output({"type": "fig-sit", "user": MATCH}, "figure"),
    Input({"type": "range-sit", "user": MATCH}, "start_date"),
    Input({"type": "range-sit", "user": MATCH}, "end_date"),
    Input({"type": "loc-sit", "user": MATCH}, "value"),
    State({"type": "fig-sit", "user": MATCH}, "id"),
)
def _render_sit_fig(start_date, end_date, loc_value, comp_id):
    uid = comp_id.get("user")
    df = _sit_df(uid, loc_value, start_date, end_date)
    return _make_line(df, "value", "SIT 센서 (착좌)", "압력")

@app.callback(
    Output({"type": "fig-with", "user": MATCH}, "figure"),
    Input({"type": "range-with", "user": MATCH}, "start_date"),
    Input({"type": "range-with", "user": MATCH}, "end_date"),
    Input({"type": "loc-with", "user": MATCH}, "value"),
    State({"type": "fig-with", "user": MATCH}, "id"),
)
def _render_with_fig(start_date, end_date, loc_value, comp_id):
    uid = comp_id.get("user")
    df = _with_df(uid, loc_value, start_date, end_date)
    # lock y-axis like before
    fig = _make_line(df, "hr", "WITH 센서 (와상)", "심박수")
    fig.update_layout(yaxis=dict(range=[20, 100]))
    return fig

@app.callback(
    Output({"type": "cam-box", "user": MATCH}, "children"),
    Input({"type": "loc-cam", "user": MATCH}, "value"),
    State({"type": "cam-box", "user": MATCH}, "id"),
)
def _render_cam_box(loc_value, comp_id):
    uid = comp_id.get("user")
    df = _cam_latest_row(uid, loc_value)
    container_style = {"backgroundColor": dark_bg, "color": text_color, "padding": "20px", "height": "100%"}
    if df.empty:
        return html.Div([
            html.Div("CAM (행동 추적)", style={"fontSize": "17px", "marginTop": "15px", "marginBottom": "10px"}),
            html.Div("❌ 해당 사용자의 CAM 데이터가 없습니다.")
        ], style=container_style)
    r = df.iloc[0]
    state = r.get("state_label", "Unknown")
    conf  = r.get("state_confidence", None)
    dist  = r.get("center_depth_m", None)
    return html.Div([
        html.Div("CAM (행동 추적)", style={"fontSize": "17px", "marginTop": "15px", "marginBottom": "10px"}),
        html.Div(state, style={"fontWeight": "bold", "backgroundColor": light_bg, "fontSize": "18px", "textAlign": "center", "marginBottom": "10px"}),
        html.Div([
            html.Div(f"🎯신뢰도: {conf:.3f}" if pd.notna(conf) else "신뢰도: -", style={"flex": "1", "textAlign": "center"}),
            html.Div(f"📏거리: {dist:.2f}m" if pd.notna(dist) else "거리: -", style={"flex": "1", "textAlign": "center"}),
        ], style={"display": "flex", "gap": "10px"})
    ], style=container_style)



# --- Remember selected locations (local storage) ----------------------------
from dash import callback_context as _ctx
from dash.dependencies import ALL

@app.callback(
    Output("loc-memory", "data"),
    Input({"type":"loc-tof","user": ALL}, "value"),
    Input({"type":"loc-sit","user": ALL}, "value"),
    Input({"type":"loc-with","user": ALL}, "value"),
    Input({"type":"loc-cam","user": ALL}, "value"),
    State({"type":"loc-tof","user": ALL}, "id"),
    State({"type":"loc-sit","user": ALL}, "id"),
    State({"type":"loc-with","user": ALL}, "id"),
    State({"type":"loc-cam","user": ALL}, "id"),
    State("loc-memory", "data"),
    prevent_initial_call=True,
)
def _remember_loc_all(tof_vals, sit_vals, with_vals, cam_vals,
                      tof_ids, sit_ids, with_ids, cam_ids, data):
    data = data or {}
    trig = _ctx.triggered[0]["prop_id"].split(".")[0] if _ctx.triggered else None
    try:
        trig_id = json.loads(trig)
    except Exception:
        trig_id = None

    def _map_vals(ids, vals):
        out = {}
        for i, cid in enumerate(ids or []):
            try:
                uid = str(cid.get("user"))
                out[uid] = vals[i] if i < len(vals) else None
            except Exception:
                pass
        return out

    mapping = {
        "loc-tof": _map_vals(tof_ids, tof_vals),
        "loc-sit": _map_vals(sit_ids, sit_vals),
        "loc-with": _map_vals(with_ids, with_vals),
        "loc-cam": _map_vals(cam_ids, cam_vals),
    }

    if trig_id and isinstance(trig_id, dict) and trig_id.get("type") in mapping:
        uid = str(trig_id.get("user"))
        sensor = trig_id["type"].split("-")[1].upper()  # TOF/SIT/WITH/CAM
        val = mapping[trig_id["type"]].get(uid)
        data.setdefault(uid, {})[sensor] = val
        return data

    for sensor_type, user_vals in mapping.items():
        sensor = sensor_type.split("-")[1].upper()
        for uid, val in user_vals.items():
            data.setdefault(uid, {})[sensor] = val
    return data

# ----------------------------------------------------------------------------
# [SERVER]
# ----------------------------------------------------------------------------

# === By-day toggle (clean) ===================================================
def _date_range_inclusive(start, end):
    s = pd.to_datetime(start).date()
    e = pd.to_datetime(end).date()
    days = []
    cur = s
    while cur <= e:
        days.append(cur)
        cur = cur + pd.Timedelta(days=1)
    return [pd.to_datetime(d).date() for d in days]

def _build_ratio_grid(dates, ratios):
    cols = len(dates) if dates else 1
    return html.Div([
        html.Div([
            html.Div(f"{d.month}/{d.day}", style={"textAlign":"center","fontWeight":700,"padding":"6px 4px","borderBottom":"1px solid #ddd"})
            for d in dates
        ], style={"display":"grid","gridTemplateColumns":f"repeat({cols}, minmax(60px,1fr))","gap":"4px"}),
        html.Div([
            html.Div(r, style={"textAlign":"center","padding":"8px 4px","fontSize":"18px","fontWeight":700})
            for r in ratios
        ], style={"display":"grid","gridTemplateColumns":f"repeat({cols}, minmax(60px,1fr))","gap":"4px"})
    ])

def _compute_daily_ratios(selected_users, start_date, end_date):
    """Return (dates, ratios_str_list) like '4/6' per day across selected users.
    분모 = 해당 날짜의 요청된 로그 수 (알람 윈도우 개수 합).
    분자 = 해당 날짜의 응답된 로그 수 (윈도우 내 answered_at 존재).
    """
    if not start_date or not end_date:
        return [], []

    alarm_df = load_alarm_log()
    if alarm_df.empty:
        days = _date_range_inclusive(start_date, end_date)
        return days, ["0/0" for _ in days]

    alarm_df['date'] = pd.to_datetime(alarm_df['start_time']).dt.date
    s_date = pd.to_datetime(start_date).date()
    e_date = pd.to_datetime(end_date).date()
    alarm_df = alarm_df[(alarm_df['date'] >= s_date) & (alarm_df['date'] <= e_date)]

    if selected_users:
        if isinstance(selected_users, (list, tuple)):
            if "ALL" not in selected_users:
                alarm_df = alarm_df[alarm_df['user_id'].astype(str).isin([str(u) for u in selected_users])]
        else:
            alarm_df = alarm_df[alarm_df['user_id'].astype(str).eq(str(selected_users))]

    if alarm_df.empty:
        days = _date_range_inclusive(start_date, end_date)
        return days, ["0/0" for _ in days]

    min_ts = alarm_df['start_time'].min()
    max_ts = alarm_df['end_time'].max()
    df_session = fetch_df(
        """
        SELECT user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL AND answered_at BETWEEN :s AND :e
        """, {"s": min_ts, "e": max_ts}
    )
    if df_session.empty:
        days = _date_range_inclusive(start_date, end_date)
        group_total = alarm_df.groupby('date').size()
        ratios = [f"0/{int(group_total.get(d,0))}" for d in days]
        return days, ratios

    df_session['answered_at'] = pd.to_datetime(df_session['answered_at'])

    days = _date_range_inclusive(start_date, end_date)
    cnt_total = {d: 0 for d in days}
    cnt_answered = {d: 0 for d in days}

    idx_by_user = {}
    for uid, g in df_session.groupby('user_id'):
        idx_by_user[uid] = g['answered_at'].sort_values().values

    for _, r in alarm_df.iterrows():
        uid = str(r['user_id'])
        s = pd.to_datetime(r['start_time'])
        e = pd.to_datetime(r['end_time'])
        d = s.date()
        if d not in cnt_total:
            continue
        cnt_total[d] += 1
        answers = idx_by_user.get(uid)
        if answers is not None:
            mask = (answers >= s.to_datetime64()) & (answers <= e.to_datetime64())
            if mask.any():
                cnt_answered[d] += 1

    ratios = [f"{int(cnt_answered.get(d,0))}/{int(cnt_total.get(d,0))}" for d in days]
    return days, ratios

@app.callback(
    Output("byday-matrix-wrap", "style"),
    Output("byday-ratio-wrap", "style"),
    Output("byday-ratio-wrap", "children"),
    Input("byday-switch", "value"),
    Input("user-dropdown", "value"),
    Input("response-stats-date-picker", "start_date"),
    Input("response-stats-date-picker", "end_date"),
    Input("interval-component", "n_intervals"),
)
def _toggle_byday(view_mode, users, start_date, end_date, _):
    if view_mode != "on":
        return {"display":"block"}, {"display":"none"}, no_update
    if not users or not start_date or not end_date:
        return {"display":"block"}, {"display":"none"}, no_update
    days, rows, _ = _compute_user_day_matrix(users, start_date, end_date)
    table = _build_ratio_table(days, rows)
    return {"display":"none"}, {"display":"block"}, table



def _compute_user_day_matrix(selected_users, start_date, end_date):
    """
    Returns (days, rows, user_ids_order) for the ratio table.
    rows is a list of dicts: {"user_id": "<label>", "uid": "<raw>", "YYYY-MM-DD": "a/b", ...}
    - 지정된 사용자(드롭다운 선택)는 로그가 없어도 항상 표시 (0/0)
    - 'ALL' 선택 시에는 DB의 모든 사용자 표시
    """
    if not start_date or not end_date:
        return [], [], []

    # --- 사용자 집합 확정
    if selected_users and "ALL" not in selected_users:
        user_ids = [str(u) for u in selected_users]
    elif selected_users and "ALL" in selected_users:
        user_ids = list(get_user_map_for(None).keys())  # DB의 모든 사용자
    else:
        user_ids = []

    # 날짜 리스트
    days = _date_range_inclusive(start_date, end_date)

    # 요청(알람) 로그
    alarm_df = load_alarm_log()
    if alarm_df.empty:
        label_map = get_user_map_for(user_ids if user_ids else None)
        rows = [{"user_id": label_map.get(uid, str(uid)), "uid": str(uid), **{str(d): "0/0" for d in days}} for uid in user_ids]
        return days, rows, user_ids

    alarm_df["date"] = pd.to_datetime(alarm_df["start_time"]).dt.date
    s_date = pd.to_datetime(start_date).date()
    e_date = pd.to_datetime(end_date).date()
    alarm_df = alarm_df[(alarm_df["date"] >= s_date) & (alarm_df["date"] <= e_date)]

    # 사용자 필터
    if user_ids:
        alarm_df = alarm_df[alarm_df["user_id"].astype(str).isin(user_ids)]

    # 응답 세션 조회 (윈도우 범위 한정)
    if alarm_df.empty:
        label_map = get_user_map_for(user_ids if user_ids else None)
        rows = [{"user_id": label_map.get(uid, str(uid)), "uid": str(uid), **{str(d): "0/0" for d in days}} for uid in user_ids]
        return days, rows, user_ids

    min_ts = alarm_df["start_time"].min()
    max_ts = alarm_df["end_time"].max()
    df_session = fetch_df(
        """
        SELECT user_id::text AS user_id, answered_at
        FROM survey_session
        WHERE answered_at IS NOT NULL AND answered_at BETWEEN :s AND :e
        """, {"s": min_ts, "e": max_ts}
    )
    df_session["answered_at"] = pd.to_datetime(df_session["answered_at"])
    if user_ids:
        df_session = df_session[df_session["user_id"].astype(str).isin(user_ids)]

    # 사용자별 응답 인덱스
    answers_by_user = {uid: g["answered_at"].sort_values().values for uid, g in df_session.groupby("user_id")}

    # 카운터 초기화 (모든 사용자 × 모든 날짜) → 기본 0/0
    total = {(uid, d): 0 for uid in user_ids for d in days}
    answered = {(uid, d): 0 for uid in user_ids for d in days}

    # 각 알람 윈도우에 대해 요청/응답 카운트
    for _, r in alarm_df.iterrows():
        uid = str(r["user_id"])
        if uid not in user_ids:
            continue
        s = pd.to_datetime(r["start_time"])
        e = pd.to_datetime(r["end_time"])
        d = s.date()
        if d not in days:
            continue
        total[(uid, d)] += 1
        arr = answers_by_user.get(uid)
        if arr is not None:
            mask = (arr >= s.to_datetime64()) & (arr <= e.to_datetime64())
            if mask.any():
                answered[(uid, d)] += 1

    # 라벨링 및 행 구성
    label_map = get_user_map_for(user_ids if user_ids else None)
    rows = []
    for uid in user_ids:
        row = {"user_id": label_map.get(uid, uid), "uid": str(uid)}
        for d in days:
            a = int(answered[(uid, d)]) if (uid, d) in answered else 0
            t = int(total[(uid, d)]) if (uid, d) in total else 0
            row[str(d)] = f"{a}/{t}"
        rows.append(row)

    return days, rows, user_ids



def _build_ratio_table(dates, rows):
    """Create a dash_table matching the OX* style, with users on rows and dates on columns.
    색 규칙: 값이 '0/0'이면 회색, 그 외엔 파란색.
    """
    # Visible columns: 사용자 + 날짜들
    date_cols = [{"name": f"{d.month}/{d.day}", "id": str(d)} for d in dates]
    columns = [{"name": "사용자 ID", "id": "user_id"}] + date_cols + [{"name": "", "id": "uid"}]  # uid는 hidden

    # Conditional styles per date column
    style_rules = []
    for d in dates:
        col_id = str(d)
        # 0/0 -> gray
        style_rules.append({"if": {"filter_query": f"{{{col_id}}} = '0/0'", "column_id": col_id}, "color": "#9ca3af"})
        # others -> blue
        style_rules.append({"if": {"filter_query": f"{{{col_id}}} != '0/0'", "column_id": col_id}, "color": "#1d4ed8", "fontWeight": "bold"})

    table = dash_table.DataTable(
        id="byday-ratio-table",
        columns=columns,
        data=rows,
        hidden_columns=["uid"],
        cell_selectable=True,
        row_selectable=False,
        selected_cells=[],
        active_cell=None,
        style_table={"overflowX": "auto", "maxHeight": "300px", "overflowY": "auto", "backgroundColor": light_bg},
        style_cell={"whiteSpace": "pre-line", "textAlign": "center", "padding": "8px", "color": text_color},
        style_header={"backgroundColor": accent, "color": "#000000", "fontWeight": "bold"},
        css=[{"selector": ".dash-spreadsheet-menu", "rule": "display: none"}],
        style_data_conditional=style_rules,
    )
    return table

app.clientside_callback( """ function(n_clicks, auto, targets, currentText) { if (!n_clicks) return currentText || ""; try { targets = targets || []; // kakao_uuid만 추출 (값이 있는 것만) const uuids = targets.map(t => t && t.kakao_uuid).filter(Boolean); if (uuids.length === 0) return "보낼 대상의 카카오 UUID가 없습니다(JSON 매핑 확인)."; const text = "[ESM 안내] 응답 누락 슬롯 안내"; // 필요시 서버에서 만들어 전달도 가능 const link = null; // 필요시 링크 지정 if (window.esmKakaoBridge && window.esmKakaoBridge.sendBulk) { window.esmKakaoBridge.sendBulk(uuids, text, link); } return "카카오 전송 요청됨 (백그라운드)"; } catch (e) { return "전송 중 오류"; } } """, 
                        Output("kakao-status", "children"), Input("btn-send-kakao", "n_clicks"), State("kakao-auto", "value"), State("kakao-targets", "data"), State("kakao-status", "children"), )

server = app.server

if __name__ == "__main__":
    app.run_server(host="0.0.0.0", port=8050, debug=True, use_reloader=False)


# @app.callback(
#     Output("kakao-targets", "data"),
#     Input("interval-component", "n_intervals"),
#     Input("byday-switch", "value"),
#     State("byday-ratio-table", "active_cell"),
#     State("byday-ratio-table", "selected_cells"),
#     State("byday-ratio-table", "data"),
#     prevent_initial_call=False,
# )
def _sync_kakao_targets(_, byday_mode, active_cell, selected_cells, byday_data):
    try:
        targets = _compute_missing_targets(byday_mode, active_cell, selected_cells, byday_data)
        if not targets:
            return []
        # def _ser(t):
        #     return {
        #         "uid": t.get("uid"),
        #         "label": t.get("label"),
        #         "start": str(pd.to_datetime(t.get("start"))),
        #         "end": str(pd.to_datetime(t.get("end"))),
        #     }
        def _ser(t): 
            return { 
                "uid": t.get("uid"), "label": t.get("label"), "start": str(pd.to_datetime(t.get("start"))), "end": str(pd.to_datetime(t.get("end"))), "kakao_uuid": t.get("kakao_uuid"), }
        return [_ser(t) for t in targets]
    except Exception:
        return dash.no_update
