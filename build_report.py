#!/usr/bin/env python3
"""Build the Q2 2026 Engineering Division Report (Excel workbook).

Sources:
  - ENGINEERING_JOB_SCHEDULING_16_JUN.xlsx  (2026 job-scheduling activities)
  - Skid_Pending_Documents.xlsx             (skid MDR document register, 2025-dated)

Output: Engineering_Division_Report_Q2_2026.xlsx
"""
import datetime
from collections import Counter
import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side, NamedStyle
from openpyxl.chart import BarChart, LineChart, PieChart, Reference, Series
from openpyxl.chart.label import DataLabelList
from openpyxl.utils import get_column_letter

SCHED = "/root/.claude/uploads/44afc668-df50-57e8-8f1f-b034291d84bb/f7fb3b20-ENGINEERING_JOB_SCHEDULING_16_JUN.xlsx"
SKID  = "/root/.claude/uploads/44afc668-df50-57e8-8f1f-b034291d84bb/151564b6-Skid_Pending_Documents.xlsx"
OUT   = "/home/user/Test/Engineering_Division_Report_Q2_2026.xlsx"

# ----------------------------- palette -----------------------------
NAVY   = "1F3864"   # headers / title
BLUE   = "2E5496"
STEEL  = "8EAADB"
LIGHT  = "D9E1F2"   # light band
LIGHT2 = "EAF0FA"
GREEN  = "548235"
GREENL = "E2EFDA"
RED    = "C00000"
REDL   = "FCE4E4"
AMBER  = "BF8F00"
AMBERL = "FFF2CC"
GREY   = "808080"
WHITE  = "FFFFFF"
DARK   = "262626"

def font(sz=11, b=False, color=DARK, italic=False):
    return Font(name="Calibri", size=sz, bold=b, color=color, italic=italic)
def fill(c):
    return PatternFill("solid", fgColor=c)
def side(): return Side(style="thin", color="BFBFBF")
BORDER = Border(left=side(), right=side(), top=side(), bottom=side())
CENTER = Alignment(horizontal="center", vertical="center", wrap_text=True)
LEFT   = Alignment(horizontal="left", vertical="center", wrap_text=True)
RIGHT  = Alignment(horizontal="right", vertical="center")

def dt(v): return v if isinstance(v, datetime.datetime) else None
def inq(d, y, q): return bool(d) and d.year == y and (d.month - 1)//3 + 1 == q

# ============================ LOAD DATA ============================
wb_s = openpyxl.load_workbook(SCHED, data_only=True)
ws_s = wb_s["Sheet1"]
S = []
for r in range(3, ws_s.max_row + 1):
    rec = [ws_s.cell(r, c).value for c in range(1, 14)]
    if all(v in (None, "") for v in rec):
        continue
    S.append(rec)

def ns(s): return str(s).strip().lower() if s else ""
def eng(rw): return str(rw[10]).strip().title() if rw[10] else "(Unassigned)"
def actname(rw): return " ".join(str(rw[2]).split()).strip() if rw[2] else "(Unspecified)"

def sched_metrics(y, q):
    sub  = [rw for rw in S if inq(dt(rw[5]), y, q)]
    n    = len(sub)
    comp = [rw for rw in sub if ns(rw[9]) == "completed"]
    inp  = [rw for rw in sub if "progress" in ns(rw[9])]
    opn  = [rw for rw in sub if ns(rw[9]) != "completed" and "progress" not in ns(rw[9])]
    ot = ote = late = 0
    for rw in comp:
        e, a = dt(rw[6]), dt(rw[7])
        if e and a:
            ote += 1
            if a <= e: ot += 1
            else: late += 1
    nods = [rw[8] for rw in sub if isinstance(rw[8], (int, float))]
    return dict(
        sub=sub, n=n, uniq=len(set(rw[0] for rw in sub if rw[0])),
        comp=len(comp), inprog=len(inp), openb=len(opn),
        comp_rate=len(comp)/n if n else 0,
        ot=ot, ote=ote, late=late, ot_rate=ot/ote if ote else 0,
        nod_avg=sum(nods)/len(nods) if nods else 0, nod_sum=sum(nods),
        act_mix=Counter(actname(rw) for rw in sub),
        eng_load=Counter(eng(rw) for rw in sub),
    )

Q1 = sched_metrics(2026, 1)
Q2 = sched_metrics(2026, 2)

# ---- Skid documents (2025-dated; aligned to Q1/Q2 for trend) ----
wb_k = openpyxl.load_workbook(SKID, data_only=True)
ws_k = wb_k["Sheet1"]
K = []
for r in range(2, ws_k.max_row + 1):
    rec = [ws_k.cell(r, c).value for c in range(1, 13)]
    if all(v in (None, "") for v in rec):
        continue
    K.append(rec)

def kstat(rw): return str(rw[9]).strip().upper() if rw[9] else "(BLANK)"
def kasg(rw):  return str(rw[6]).strip() if rw[6] else "(Unassigned)"

def skid_metrics(q):
    sub  = [rw for rw in K if inq(dt(rw[8]), 2025, q)]
    comp = [rw for rw in sub if kstat(rw) == "COMPLETED"]
    pend = [rw for rw in sub if kstat(rw) == "PENDING"]
    ot = ote = 0
    for rw in comp:
        p, a = dt(rw[8]), dt(rw[10])
        if p and a:
            ote += 1
            if a <= p: ot += 1
    return dict(
        n=len(sub), uniq=len(set(rw[0] for rw in sub if rw[0])),
        comp=len(comp), pend=len(pend), comp_rate=len(comp)/len(sub) if sub else 0,
        ot=ot, ote=ote, ot_rate=ot/ote if ote else 0,
        asg=Counter(kasg(rw) for rw in sub),
    )

SK1 = skid_metrics(1)
SK2 = skid_metrics(2)
SKID_PEND_TOTAL = sum(1 for rw in K if kstat(rw) == "PENDING")
SKID_COMP_TOTAL = sum(1 for rw in K if kstat(rw) == "COMPLETED")
SKID_JOBS       = len(set(rw[0] for rw in K if rw[0]))
SKID_PEND_ASG   = Counter(kasg(rw) for rw in K if kstat(rw) == "PENDING")

# ============================ WORKBOOK ============================
wb = openpyxl.Workbook()

def style_cell(c, value=None, f=None, fl=None, align=None, border=False, numfmt=None):
    if value is not None: c.value = value
    if f: c.font = f
    if fl: c.fill = fl
    if align: c.alignment = align
    if border: c.border = BORDER
    if numfmt: c.number_format = numfmt
    return c

def pct(x): return f"{x*100:.1f}%"
def delta_arrow(d, good_up=True):
    if abs(d) < 1e-9: return "→ 0"
    arr = "▲" if d > 0 else "▼"
    return f"{arr} {d:+.1f}"

# ----------------------------------------------------------------- COVER
cov = wb.active
cov.title = "Cover"
cov.sheet_view.showGridLines = False
for col, w in zip("ABCDEFGH", [3, 22, 22, 22, 22, 22, 22, 3]):
    cov.column_dimensions[col].width = w
for r in range(1, 40):
    cov.row_dimensions[r].height = 20

cov.merge_cells("B2:G2"); cov.row_dimensions[2].height = 10
style_cell(cov["B2"], fl=fill(NAVY))
for rr in (3, 4, 5):
    cov.merge_cells(f"B{rr}:G{rr}")
    style_cell(cov[f"B{rr}"], fl=fill(NAVY))
cov.row_dimensions[4].height = 46
style_cell(cov["B4"], "ENGINEERING DIVISION", font(28, True, WHITE), fill(NAVY), CENTER)
cov.merge_cells("B6:G6"); cov.row_dimensions[6].height = 34
style_cell(cov["B6"], "Quarterly Performance Report", font(18, True, NAVY), fill(WHITE), CENTER)
cov.merge_cells("B7:G7"); cov.row_dimensions[7].height = 30
style_cell(cov["B7"], "Q2 2026  (Apr – Jun 2026)", font(16, True, BLUE), fill(WHITE), CENTER)
cov.merge_cells("B8:G8")
style_cell(cov["B8"], "with comparison to Q1 2026", font(12, False, GREY, italic=True), fill(WHITE), CENTER)

# meta box
meta = [
    ("Reporting period", "Q2 2026 (1 Apr – 30 Jun 2026)"),
    ("Comparison baseline", "Q1 2026 (1 Jan – 31 Mar 2026)"),
    ("Data sources", "Engineering Job Scheduling; Skid MDR Register"),
    ("Data cut-off", "16 June 2026"),
    ("Report date", "22 June 2026"),
    ("Prepared by", "Engineering Division"),
]
rr = 11
for label, val in meta:
    cov.merge_cells(f"B{rr}:C{rr}")
    style_cell(cov[f"B{rr}"], label, font(11, True, WHITE), fill(BLUE), LEFT, True)
    cov.merge_cells(f"D{rr}:G{rr}")
    style_cell(cov[f"D{rr}"], val, font(11), fill(LIGHT2), LEFT, True)
    cov.row_dimensions[rr].height = 22
    rr += 1

rr += 1
cov.merge_cells(f"B{rr}:G{rr}")
style_cell(cov[f"B{rr}"], "Scope & Data Notes", font(12, True, NAVY), fill(LIGHT), LEFT, True)
rr += 1
notes = [
    "1.  Job-scheduling KPIs are derived from activity Start Date within each quarter.",
    "2.  Q2 2026 reflects activity through the 16 Jun 2026 data cut-off (partial quarter).",
    "3.  'NOD' = planned turnaround in days (End Date − Start Date).",
    "4.  On-time = Actual Completion Date on or before the planned End Date.",
    "5.  Skid MDR document data is 2025-dated; its Q1/Q2 split is aligned to the",
    "     quarterly timeline for trend comparison (calendar year differs — see Skid sheet).",
]
for ln in notes:
    cov.merge_cells(f"B{rr}:G{rr}")
    style_cell(cov[f"B{rr}"], ln, font(10), fill(WHITE), LEFT)
    cov.row_dimensions[rr].height = 16
    rr += 1

# ----------------------------------------------------------------- EXEC SUMMARY
es = wb.create_sheet("Executive Summary")
es.sheet_view.showGridLines = False
for col, w in zip("ABCDEFGHI", [2.5, 26, 15, 15, 15, 16, 26, 26, 2.5]):
    es.column_dimensions[col].width = w
es.merge_cells("B2:H2"); es.row_dimensions[2].height = 30
style_cell(es["B2"], "EXECUTIVE SUMMARY — Q2 2026 vs Q1 2026", font(16, True, WHITE), fill(NAVY), LEFT)
es.merge_cells("B3:H3")
style_cell(es["B3"], "Engineering Job Scheduling — core delivery KPIs", font(11, False, GREY, italic=True), fill(WHITE), LEFT)

# KPI cards: (title, q1val, q2val, fmt, good_up, deltatext)
def card(anchor_row, anchor_col, title, q2txt, sub, accent):
    c0 = anchor_col
    es.merge_cells(start_row=anchor_row, start_column=c0, end_row=anchor_row, end_column=c0+1)
    style_cell(es.cell(anchor_row, c0), title, font(10, True, WHITE), fill(accent), CENTER, True)
    es.merge_cells(start_row=anchor_row+1, start_column=c0, end_row=anchor_row+1, end_column=c0+1)
    style_cell(es.cell(anchor_row+1, c0), q2txt, font(22, True, accent), fill(LIGHT2), CENTER, True)
    es.merge_cells(start_row=anchor_row+2, start_column=c0, end_row=anchor_row+2, end_column=c0+1)
    style_cell(es.cell(anchor_row+2, c0), sub, font(9, False, GREY), fill(LIGHT2), CENTER, True)
    es.row_dimensions[anchor_row].height = 20
    es.row_dimensions[anchor_row+1].height = 34
    es.row_dimensions[anchor_row+2].height = 18

cards = [
    ("Activities Completed", f"{Q2['comp']}", f"Q1: {Q1['comp']}  ({delta_arrow(Q2['comp']-Q1['comp'])})", BLUE),
    ("Completion Rate", pct(Q2['comp_rate']), f"Q1: {pct(Q1['comp_rate'])}  ({delta_arrow((Q2['comp_rate']-Q1['comp_rate'])*100)} pp)", GREEN),
    ("On-Time Delivery", pct(Q2['ot_rate']), f"Q1: {pct(Q1['ot_rate'])}  ({delta_arrow((Q2['ot_rate']-Q1['ot_rate'])*100)} pp)", GREEN),
    ("Avg Turnaround (days)", f"{Q2['nod_avg']:.2f}", f"Q1: {Q1['nod_avg']:.2f}  ({delta_arrow(Q2['nod_avg']-Q1['nod_avg'])})", AMBER),
    ("Active Job Orders", f"{Q2['uniq']}", f"Q1: {Q1['uniq']}  ({delta_arrow(Q2['uniq']-Q1['uniq'])})", BLUE),
    ("Late Activities", f"{Q2['late']}", f"Q1: {Q1['late']}  ({delta_arrow(Q2['late']-Q1['late'])})", RED),
]
positions = [(5,2),(5,4),(5,6),(9,2),(9,4),(9,6)]
for (title, val, sub, accent), (r, c) in zip(cards, positions):
    card(r, c, title, val, sub, accent)

# Key takeaways
tr = 13
es.merge_cells(f"B{tr}:H{tr}")
style_cell(es[f"B{tr}"], "Key Takeaways", font(13, True, WHITE), fill(BLUE), LEFT)
es.row_dimensions[tr].height = 24
takeaways = [
    ("On-time delivery improved",
     f"On-time completion rose from {pct(Q1['ot_rate'])} in Q1 to {pct(Q2['ot_rate'])} in Q2, "
     f"and late activities fell from {Q1['late']} to {Q2['late']}.", GREENL, GREEN),
    ("Turnaround tightened",
     f"Average planned turnaround improved from {Q1['nod_avg']:.2f} to {Q2['nod_avg']:.2f} days "
     f"per activity — faster scheduled cycle times.", GREENL, GREEN),
    ("Lower throughput (partial quarter)",
     f"Q2 shows {Q2['n']} activities vs {Q1['n']} in Q1. Q2 data ends 16 Jun, so the quarter is "
     f"~2/3 elapsed; full-quarter volume is expected to land near Q1 levels.", AMBERL, AMBER),
    ("Completion rate slightly down",
     f"Completion rate eased from {pct(Q1['comp_rate'])} to {pct(Q2['comp_rate'])}, reflecting "
     f"{Q2['inprog']} in-progress and {Q2['openb']} open items still inside the active quarter.", AMBERL, AMBER),
    ("Skid MDR backlog needs attention",
     f"The skid document register carries {SKID_PEND_TOTAL} pending documents across {SKID_JOBS} jobs; "
     f"Kareem Malik ({SKID_PEND_ASG['Kareem Malik']}) and Ashif Mujthaba ({SKID_PEND_ASG['Ashif Mujthaba']}) "
     f"hold the largest shares.", REDL, RED),
]
tr += 1
for head, body, bg, accent in takeaways:
    es.merge_cells(f"B{tr}:H{tr}")
    style_cell(es[f"B{tr}"], head, font(11, True, accent), fill(bg), LEFT, True)
    es.row_dimensions[tr].height = 18
    tr += 1
    es.merge_cells(f"B{tr}:H{tr}")
    style_cell(es[f"B{tr}"], body, font(10), fill(WHITE), LEFT, True)
    es.row_dimensions[tr].height = 30
    tr += 1

# ----------------------------------------------------------------- KPI COMPARISON
kc = wb.create_sheet("KPI Comparison")
kc.sheet_view.showGridLines = False
for col, w in zip("ABCDEF", [3, 38, 15, 15, 15, 30]):
    kc.column_dimensions[col].width = w
kc.merge_cells("B2:F2"); kc.row_dimensions[2].height = 28
style_cell(kc["B2"], "KPI COMPARISON — Engineering Job Scheduling", font(15, True, WHITE), fill(NAVY), LEFT)

hdr = ["KPI", "Q1 2026", "Q2 2026", "Change", "Interpretation"]
hr = 4
for i, h in enumerate(hdr):
    style_cell(kc.cell(hr, 2+i), h, font(11, True, WHITE), fill(BLUE), CENTER, True)
kc.row_dimensions[hr].height = 22

# rows: label, q1, q2, change-string, good (None/True/False for color), note
def chg_pp(a, b): return f"{(b-a)*100:+.1f} pp"
def chg_n(a, b):  return f"{b-a:+d}"
def chg_f(a, b):  return f"{b-a:+.2f}"
rows = [
    ("Total activities scheduled", Q1['n'], Q2['n'], chg_n(Q1['n'], Q2['n']), None,
     "Q2 partial (cut-off 16 Jun)"),
    ("Unique job orders worked", Q1['uniq'], Q2['uniq'], chg_n(Q1['uniq'], Q2['uniq']), None,
     "Active project breadth"),
    ("Activities completed", Q1['comp'], Q2['comp'], chg_n(Q1['comp'], Q2['comp']), None,
     "Delivered output"),
    ("Completion rate", pct(Q1['comp_rate']), pct(Q2['comp_rate']),
     chg_pp(Q1['comp_rate'], Q2['comp_rate']), Q2['comp_rate'] >= Q1['comp_rate'],
     "Share of scheduled work completed"),
    ("In-progress activities", Q1['inprog'], Q2['inprog'], chg_n(Q1['inprog'], Q2['inprog']), None,
     "Work-in-progress at cut-off"),
    ("Open / not-started", Q1['openb'], Q2['openb'], chg_n(Q1['openb'], Q2['openb']), None,
     "Backlog inside quarter"),
    ("On-time deliveries", Q1['ot'], Q2['ot'], chg_n(Q1['ot'], Q2['ot']), Q2['ot'] >= 0,
     "Completed on/before End Date"),
    ("Late deliveries", Q1['late'], Q2['late'], chg_n(Q1['late'], Q2['late']), Q2['late'] <= Q1['late'],
     "Completed after End Date"),
    ("On-time delivery rate", pct(Q1['ot_rate']), pct(Q2['ot_rate']),
     chg_pp(Q1['ot_rate'], Q2['ot_rate']), Q2['ot_rate'] >= Q1['ot_rate'],
     "Schedule reliability"),
    ("Avg planned turnaround (days)", f"{Q1['nod_avg']:.2f}", f"{Q2['nod_avg']:.2f}",
     chg_f(Q1['nod_avg'], Q2['nod_avg']), Q2['nod_avg'] <= Q1['nod_avg'],
     "Lower = faster cycle time"),
    ("Total planned engineering-days", Q1['nod_sum'], Q2['nod_sum'], chg_n(Q1['nod_sum'], Q2['nod_sum']), None,
     "Scheduled effort volume"),
]
r = hr + 1
for i, (label, q1, q2, ch, good, note) in enumerate(rows):
    band = LIGHT2 if i % 2 == 0 else WHITE
    style_cell(kc.cell(r, 2), label, font(10, True), fill(band), LEFT, True)
    style_cell(kc.cell(r, 3), q1, font(10), fill(band), CENTER, True)
    style_cell(kc.cell(r, 4), q2, font(10, True), fill(band), CENTER, True)
    cf = font(10, True, GREEN if good else (RED if good is False else GREY))
    cfl = fill(GREENL if good else (REDL if good is False else band))
    style_cell(kc.cell(r, 5), ch, cf, cfl, CENTER, True)
    style_cell(kc.cell(r, 6), note, font(9, False, GREY), fill(band), LEFT, True)
    kc.row_dimensions[r].height = 20
    r += 1

# small data table for charts (hidden-ish, placed to the right)
ch_r = hr  # reuse rows region to the right at col H
base = 8
kc.cell(ch_r, base, "Metric")
kc.cell(ch_r, base+1, "Q1 2026")
kc.cell(ch_r, base+2, "Q2 2026")
chart_data = [
    ("Completion %", round(Q1['comp_rate']*100, 1), round(Q2['comp_rate']*100, 1)),
    ("On-Time %", round(Q1['ot_rate']*100, 1), round(Q2['ot_rate']*100, 1)),
]
for i, (m, a, b) in enumerate(chart_data, 1):
    kc.cell(ch_r+i, base, m); kc.cell(ch_r+i, base+1, a); kc.cell(ch_r+i, base+2, b)
rate_chart = BarChart()
rate_chart.type = "col"; rate_chart.title = "Quality KPIs: Q1 vs Q2 (%)"
rate_chart.y_axis.title = "Percent"; rate_chart.height = 7.5; rate_chart.width = 13
data = Reference(kc, min_col=base+1, max_col=base+2, min_row=ch_r, max_row=ch_r+2)
cats = Reference(kc, min_col=base, max_col=base, min_row=ch_r+1, max_row=ch_r+2)
rate_chart.add_data(data, titles_from_data=True); rate_chart.set_categories(cats)
rate_chart.dLbls = DataLabelList(); rate_chart.dLbls.showVal = True
kc.add_chart(rate_chart, "B18")

# volume chart
vbase = 8; vr = ch_r + 5
kc.cell(vr, base, "Metric"); kc.cell(vr, base+1, "Q1 2026"); kc.cell(vr, base+2, "Q2 2026")
vol = [("Scheduled", Q1['n'], Q2['n']), ("Completed", Q1['comp'], Q2['comp']),
       ("Late", Q1['late'], Q2['late'])]
for i, (m, a, b) in enumerate(vol, 1):
    kc.cell(vr+i, base, m); kc.cell(vr+i, base+1, a); kc.cell(vr+i, base+2, b)
vol_chart = BarChart()
vol_chart.type = "col"; vol_chart.title = "Volume KPIs: Q1 vs Q2"
vol_chart.y_axis.title = "Activities"; vol_chart.height = 7.5; vol_chart.width = 13
vdata = Reference(kc, min_col=base+1, max_col=base+2, min_row=vr, max_row=vr+3)
vcats = Reference(kc, min_col=base, max_col=base, min_row=vr+1, max_row=vr+3)
vol_chart.add_data(vdata, titles_from_data=True); vol_chart.set_categories(vcats)
vol_chart.dLbls = DataLabelList(); vol_chart.dLbls.showVal = True
kc.add_chart(vol_chart, "B34")
# move helper tables out of view
for col in range(base, base+3):
    kc.column_dimensions[get_column_letter(col)].hidden = True

# ----------------------------------------------------------------- ACTIVITY MIX
am = wb.create_sheet("Activity Mix")
am.sheet_view.showGridLines = False
for col, w in zip("ABCDE", [3, 42, 13, 13, 13]):
    am.column_dimensions[col].width = w
am.merge_cells("B2:E2"); am.row_dimensions[2].height = 28
style_cell(am["B2"], "ACTIVITY MIX — Top Engineering Activity Types", font(15, True, WHITE), fill(NAVY), LEFT)
am.merge_cells("B3:E3")
style_cell(am["B3"], "Count of scheduled activities by type (Q1 vs Q2 2026)", font(10, False, GREY, italic=True), fill(WHITE), LEFT)

allacts = set(Q1['act_mix']) | set(Q2['act_mix'])
ranked = sorted(allacts, key=lambda a: Q1['act_mix'][a] + Q2['act_mix'][a], reverse=True)[:15]
hr = 5
for i, h in enumerate(["Activity Type", "Q1 2026", "Q2 2026", "Total"]):
    style_cell(am.cell(hr, 2+i), h, font(11, True, WHITE), fill(BLUE), CENTER, True)
am.row_dimensions[hr].height = 20
r = hr + 1
for i, a in enumerate(ranked):
    band = LIGHT2 if i % 2 == 0 else WHITE
    q1c, q2c = Q1['act_mix'][a], Q2['act_mix'][a]
    style_cell(am.cell(r, 2), a, font(10), fill(band), LEFT, True)
    style_cell(am.cell(r, 3), q1c, font(10), fill(band), CENTER, True)
    style_cell(am.cell(r, 4), q2c, font(10, True), fill(band), CENTER, True)
    style_cell(am.cell(r, 5), q1c+q2c, font(10, True, NAVY), fill(band), CENTER, True)
    am.row_dimensions[r].height = 18
    r += 1
mix_chart = BarChart()
mix_chart.type = "bar"; mix_chart.title = "Top Activity Types — Q1 vs Q2 2026"
mix_chart.height = 12; mix_chart.width = 20
mdata = Reference(am, min_col=3, max_col=4, min_row=hr, max_row=r-1)
mcats = Reference(am, min_col=2, max_col=2, min_row=hr+1, max_row=r-1)
mix_chart.add_data(mdata, titles_from_data=True); mix_chart.set_categories(mcats)
am.add_chart(mix_chart, "G5")

# ----------------------------------------------------------------- ENGINEER WORKLOAD
ew = wb.create_sheet("Engineer Workload")
ew.sheet_view.showGridLines = False
for col, w in zip("ABCDEF", [3, 22, 13, 13, 13, 16]):
    ew.column_dimensions[col].width = w
ew.merge_cells("B2:F2"); ew.row_dimensions[2].height = 28
style_cell(ew["B2"], "ENGINEER WORKLOAD & PRODUCTIVITY", font(15, True, WHITE), fill(NAVY), LEFT)
ew.merge_cells("B3:F3")
style_cell(ew["B3"], "Activities scheduled per engineer (Q1 vs Q2 2026)", font(10, False, GREY, italic=True), fill(WHITE), LEFT)

engs = [e for e in (set(Q1['eng_load']) | set(Q2['eng_load']))]
engs = sorted(engs, key=lambda e: Q1['eng_load'][e] + Q2['eng_load'][e], reverse=True)
hr = 5
for i, h in enumerate(["Engineer", "Q1 2026", "Q2 2026", "Total", "Share of Total"]):
    style_cell(ew.cell(hr, 2+i), h, font(11, True, WHITE), fill(BLUE), CENTER, True)
ew.row_dimensions[hr].height = 20
grand = sum(Q1['eng_load'][e] + Q2['eng_load'][e] for e in engs)
r = hr + 1
for i, e in enumerate(engs):
    band = LIGHT2 if i % 2 == 0 else WHITE
    q1c, q2c = Q1['eng_load'][e], Q2['eng_load'][e]
    tot = q1c + q2c
    style_cell(ew.cell(r, 2), e, font(10, True), fill(band), LEFT, True)
    style_cell(ew.cell(r, 3), q1c, font(10), fill(band), CENTER, True)
    style_cell(ew.cell(r, 4), q2c, font(10, True), fill(band), CENTER, True)
    style_cell(ew.cell(r, 5), tot, font(10, True, NAVY), fill(band), CENTER, True)
    style_cell(ew.cell(r, 6), tot/grand if grand else 0, font(10), fill(band), CENTER, True, "0.0%")
    ew.row_dimensions[r].height = 18
    r += 1
# totals row
style_cell(ew.cell(r, 2), "TOTAL", font(10, True, WHITE), fill(NAVY), LEFT, True)
style_cell(ew.cell(r, 3), Q1['n'], font(10, True, WHITE), fill(NAVY), CENTER, True)
style_cell(ew.cell(r, 4), Q2['n'], font(10, True, WHITE), fill(NAVY), CENTER, True)
style_cell(ew.cell(r, 5), grand, font(10, True, WHITE), fill(NAVY), CENTER, True)
style_cell(ew.cell(r, 6), 1.0, font(10, True, WHITE), fill(NAVY), CENTER, True, "0.0%")

ew_chart = BarChart()
ew_chart.type = "col"; ew_chart.title = "Engineer Workload — Q1 vs Q2 2026"
ew_chart.y_axis.title = "Activities"; ew_chart.height = 10; ew_chart.width = 20
edata = Reference(ew, min_col=3, max_col=4, min_row=hr, max_row=r-1)
ecats = Reference(ew, min_col=2, max_col=2, min_row=hr+1, max_row=r-1)
ew_chart.add_data(edata, titles_from_data=True); ew_chart.set_categories(ecats)
ew.add_chart(ew_chart, "H5")

# ----------------------------------------------------------------- SKID MDR
sk = wb.create_sheet("Skid MDR Status")
sk.sheet_view.showGridLines = False
for col, w in zip("ABCDEF", [3, 34, 14, 14, 14, 14]):
    sk.column_dimensions[col].width = w
sk.merge_cells("B2:F2"); sk.row_dimensions[2].height = 28
style_cell(sk["B2"], "SKID PROJECTS — MDR DOCUMENT STATUS", font(15, True, WHITE), fill(NAVY), LEFT)
sk.merge_cells("B3:F3")
style_cell(sk["B3"], "Master Document Register submission tracking (Skid Pending Documents)", font(10, False, GREY, italic=True), fill(WHITE), LEFT)
sk.merge_cells("B4:F4")
style_cell(sk["B4"], "Note: source dates are in calendar year 2025; Q1/Q2 split shown for "
           "quarter-over-quarter trend. Totals reflect current register snapshot.",
           font(9, False, RED, italic=True), fill(REDL), LEFT, True)
sk.row_dimensions[4].height = 28

# summary stat strip
stats = [("Total documents", len(K)), ("Completed", SKID_COMP_TOTAL),
         ("Pending", SKID_PEND_TOTAL), ("Skid jobs", SKID_JOBS)]
sr = 6
for i, (t, v) in enumerate(stats):
    c0 = 2 + i
    style_cell(sk.cell(sr, c0), t, font(9, True, WHITE), fill(BLUE), CENTER, True)
    style_cell(sk.cell(sr+1, c0), v, font(16, True, NAVY), fill(LIGHT2), CENTER, True)
sk.row_dimensions[sr].height = 18; sk.row_dimensions[sr+1].height = 28

# Q1 vs Q2 table
hr = 9
for i, h in enumerate(["Skid MDR KPI", "Q1", "Q2", "Change"]):
    style_cell(sk.cell(hr, 2+i), h, font(11, True, WHITE), fill(BLUE), CENTER, True)
sk.row_dimensions[hr].height = 20
skrows = [
    ("Documents due (planned)", SK1['n'], SK2['n'], chg_n(SK1['n'], SK2['n']), None),
    ("Completed", SK1['comp'], SK2['comp'], chg_n(SK1['comp'], SK2['comp']), None),
    ("Pending", SK1['pend'], SK2['pend'], chg_n(SK1['pend'], SK2['pend']), SK2['pend'] <= SK1['pend']),
    ("Completion rate", pct(SK1['comp_rate']), pct(SK2['comp_rate']),
     chg_pp(SK1['comp_rate'], SK2['comp_rate']), SK2['comp_rate'] >= SK1['comp_rate']),
    ("On-time submission rate", pct(SK1['ot_rate']), pct(SK2['ot_rate']),
     chg_pp(SK1['ot_rate'], SK2['ot_rate']), SK2['ot_rate'] >= SK1['ot_rate']),
    ("Skid jobs touched", SK1['uniq'], SK2['uniq'], chg_n(SK1['uniq'], SK2['uniq']), None),
]
r = hr + 1
for i, (label, a, b, ch, good) in enumerate(skrows):
    band = LIGHT2 if i % 2 == 0 else WHITE
    style_cell(sk.cell(r, 2), label, font(10, True), fill(band), LEFT, True)
    style_cell(sk.cell(r, 3), a, font(10), fill(band), CENTER, True)
    style_cell(sk.cell(r, 4), b, font(10, True), fill(band), CENTER, True)
    cf = font(10, True, GREEN if good else (RED if good is False else GREY))
    cfl = fill(GREENL if good else (REDL if good is False else band))
    style_cell(sk.cell(r, 5), ch, cf, cfl, CENTER, True)
    sk.row_dimensions[r].height = 18
    r += 1

# pending backlog by assignee
pr = r + 1
sk.merge_cells(f"B{pr}:E{pr}")
style_cell(sk[f"B{pr}"], "Pending Document Backlog by Assignee", font(12, True, WHITE), fill(BLUE), LEFT)
sk.row_dimensions[pr].height = 22
pr += 1
style_cell(sk.cell(pr, 2), "Assignee", font(11, True, WHITE), fill(STEEL), LEFT, True)
style_cell(sk.cell(pr, 3), "Pending Docs", font(11, True, WHITE), fill(STEEL), CENTER, True)
pr += 1
pend_sorted = SKID_PEND_ASG.most_common()
pstart = pr
for i, (a, v) in enumerate(pend_sorted):
    band = LIGHT2 if i % 2 == 0 else WHITE
    style_cell(sk.cell(pr, 2), a, font(10), fill(band), LEFT, True)
    style_cell(sk.cell(pr, 3), v, font(10, True, RED), fill(band), CENTER, True)
    sk.row_dimensions[pr].height = 18
    pr += 1
pie = PieChart()
pie.title = "Pending Backlog Share by Assignee"
pdata = Reference(sk, min_col=3, min_row=pstart-1, max_row=pr-1)
pcats = Reference(sk, min_col=2, min_row=pstart, max_row=pr-1)
pie.add_data(pdata, titles_from_data=True); pie.set_categories(pcats)
pie.dLbls = DataLabelList(); pie.dLbls.showPercent = True
pie.height = 9; pie.width = 13
sk.add_chart(pie, "G9")

# skid trend bar
tb = pr + 1
sk.cell(tb, 7, "Stage"); sk.cell(tb, 8, "Q1"); sk.cell(tb, 9, "Q2")
trend = [("Completed", SK1['comp'], SK2['comp']), ("Pending", SK1['pend'], SK2['pend'])]
for i, (m, a, b) in enumerate(trend, 1):
    sk.cell(tb+i, 7, m); sk.cell(tb+i, 8, a); sk.cell(tb+i, 9, b)
skbar = BarChart(); skbar.type = "col"; skbar.title = "Skid Docs: Completed vs Pending (Q1→Q2)"
skbar.height = 8; skbar.width = 13
sdata = Reference(sk, min_col=8, max_col=9, min_row=tb, max_row=tb+2)
scats = Reference(sk, min_col=7, max_col=7, min_row=tb+1, max_row=tb+2)
skbar.add_data(sdata, titles_from_data=True); skbar.set_categories(scats)
skbar.dLbls = DataLabelList(); skbar.dLbls.showVal = True
sk.add_chart(skbar, "G26")
for col in (7, 8, 9):
    sk.column_dimensions[get_column_letter(col)].hidden = True

# ----------------------------------------------------------------- DIVISION COMBINED
dv = wb.create_sheet("Division KPIs")
dv.sheet_view.showGridLines = False
for col, w in zip("ABCDE", [3, 40, 18, 18, 18]):
    dv.column_dimensions[col].width = w
dv.merge_cells("B2:E2"); dv.row_dimensions[2].height = 28
style_cell(dv["B2"], "ENGINEERING DIVISION — COMBINED OUTPUT", font(15, True, WHITE), fill(NAVY), LEFT)
dv.merge_cells("B3:E3")
style_cell(dv["B3"], "Job-scheduling activities + skid MDR documents", font(10, False, GREY, italic=True), fill(WHITE), LEFT)

hr = 5
for i, h in enumerate(["Work Stream", "Items Completed", "Open / Pending", "Quality"]):
    style_cell(dv.cell(hr, 2+i), h, font(11, True, WHITE), fill(BLUE), CENTER, True)
dv.row_dimensions[hr].height = 22
combined = [
    ("Job-scheduling activities (Q2 2026)", Q2['comp'], Q2['inprog']+Q2['openb'],
     f"On-time {pct(Q2['ot_rate'])}"),
    ("Job-scheduling activities (Q1 2026)", Q1['comp'], Q1['inprog']+Q1['openb'],
     f"On-time {pct(Q1['ot_rate'])}"),
    ("Skid MDR documents (register)", SKID_COMP_TOTAL, SKID_PEND_TOTAL,
     f"{pct(SKID_COMP_TOTAL/len(K))} complete"),
]
r = hr + 1
for i, (label, comp, open_, qual) in enumerate(combined):
    band = LIGHT2 if i % 2 == 0 else WHITE
    style_cell(dv.cell(r, 2), label, font(10, True), fill(band), LEFT, True)
    style_cell(dv.cell(r, 3), comp, font(10, True, GREEN), fill(band), CENTER, True)
    style_cell(dv.cell(r, 4), open_, font(10, True, AMBER), fill(band), CENTER, True)
    style_cell(dv.cell(r, 5), qual, font(10), fill(band), CENTER, True)
    dv.row_dimensions[r].height = 20
    r += 1

# recommendations
r += 1
dv.merge_cells(f"B{r}:E{r}")
style_cell(dv[f"B{r}"], "Recommendations / Focus for Q3 2026", font(13, True, WHITE), fill(BLUE), LEFT)
dv.row_dimensions[r].height = 24
recs = [
    "Sustain the on-time gain: lock in the Q2 schedule-reliability improvement "
    f"({pct(Q1['ot_rate'])} → {pct(Q2['ot_rate'])}) by holding planning discipline on turnaround.",
    f"Clear the skid MDR backlog: {SKID_PEND_TOTAL} pending documents are concentrated on two "
    "assignees — rebalance load and set weekly submission targets.",
    f"Close out in-quarter WIP: {Q2['inprog']} in-progress and {Q2['openb']} open activities should "
    "be driven to completion before quarter close.",
    "Validate full-quarter Q2 volume after 30 Jun: current figures are through 16 Jun and will rise.",
    "Watch workload concentration: a few engineers carry the majority of scheduled activities — "
    "consider cross-loading to reduce single-point dependency.",
]
r += 1
for i, rec in enumerate(recs):
    dv.merge_cells(f"B{r}:E{r}")
    style_cell(dv[f"B{r}"], f"{i+1}.  {rec}", font(10), fill(LIGHT2 if i % 2 == 0 else WHITE), LEFT, True)
    dv.row_dimensions[r].height = 30
    r += 1

# ----------------------------------------------------------------- save
wb.save(OUT)
print("Saved:", OUT)
print("Sheets:", wb.sheetnames)
