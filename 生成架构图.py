"""Render the architecture poster locally; requires Pillow and Microsoft YaHei."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
S = 2
W, H = 2400, 1880
im = Image.new('RGB', (W*S, H*S), '#fcfcfb')
d = ImageDraw.Draw(im)
FONT = 'C:/Windows/Fonts/msyh.ttc'
BOLD = 'C:/Windows/Fonts/msyhbd.ttc'
INK, MUTED = '#203044', '#526071'
COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4']

def tint(c, alpha=.09):
    rgb = tuple(int(c[i:i+2],16) for i in (1,3,5))
    return tuple(round(255*(1-alpha)+v*alpha) for v in rgb)

def box(x,y,w,h,fill='white',outline='#e3e8ee',r=22,width=1):
    d.rounded_rectangle((x*S,y*S,(x+w)*S,(y+h)*S),radius=r*S,fill=fill,outline=outline,width=width*S)

def text(s,x,y,size=24,bold=False,fill=INK,center=False):
    f=ImageFont.truetype(BOLD if bold else FONT,size*S)
    if center:
        x -= d.textlength(s,font=f)/(2*S)
    d.text((x*S,y*S),s,font=f,fill=fill)

def card(x,y,w,h,title,lines,c=0):
    box(x,y,w,h,tint(COLORS[c]),tint(COLORS[c],.23),16)
    text(title,x+w/2,y+15,25,True,center=True)
    for i,line in enumerate(lines):
        f=ImageFont.truetype(FONT,21*S)
        assert d.textlength(line,font=f) <= (w-20)*S, (title,line)
        text(line,x+w/2,y+55+30*i,21,fill=MUTED,center=True)

def layer(n,y,h,name,lines):
    c=COLORS[n-1]
    box(72,y,224,h,tint(c,.19),tint(c,.35),24)
    box(72,y,9,h,c,c,4)
    mid=y+h/2
    text(f'{n:02}',184,mid-66,35,True,center=True)
    text(name,184,mid-12,27,True,center=True)
    for i,line in enumerate(lines): text(line,184,mid+33+i*29,20,fill=MUTED,center=True)
    box(330,y,1998,h,'white','#e3e8ee',24)

box(72,40,2256,128,'#f7fafc','#e8edf2',26)
text('企业智能知识库系统架构图',1200,55,37,True,center=True)
text('V3.1  /  从零建设 · 企业内部生产系统 · 合规已通过可调公有云 API · ParadeDB(BM25)+ pgvector 统一检索',1200,112,21,fill=MUTED,center=True)

layer(1,192,146,'接入与交互层',['浏览器与业务入口'])
card(360,212,630,105,'浏览器访问端',['企业员工、审核人、系统管理员'],0)
card(1014,212,630,105,'业务入口场景',['文档中心、搜索、AI 助手、管理后台'],2)
card(1668,212,630,105,'扩展业务入口',['审核、统计、通知按需排期'],1)

layer(2,362,214,'前端应用层',['React 单页应用','路由、状态与交互'])
text('前端单页应用',1329,374,28,True,center=True)
text('React + TypeScript + Vite + Ant Design  ·  具体版本待定',1329,416,21,fill=MUTED,center=True)
for x,title,lines,c in [
    (360,'入口与路由',['登录、懒加载、权限守卫'],0),
    (752,'布局与页面',['文档、搜索、对话、管理'],2),
    (1144,'状态与服务',['状态管理、请求封装'],1),
    (1536,'文档预览',['Markdown、PDF、Office¹'],3),
    (1928,'请求治理',['认证、异常处理、SSE'],4)]:
    card(x,460,370,96,title,lines,c)

layer(3,600,138,'网关治理层',['统一入口与配置'])
card(360,619,1280,99,'API 网关与统一治理  [候选]',['Spring Cloud Gateway / 企业现有入口 · 认证、路由、CORS、限流、SSE 透传'],1)
card(1664,619,634,99,'配置与注册发现  [候选]',['Nacos / 企业现有配置平台'],0)

layer(4,762,556,'应用服务层',['业务模块化单体','独立 Worker 与 AI'])
text('三个应用角色（建议部署方案）',1329,773,28,True,center=True)
text('Maven 多模块 + Spring Boot + MyBatis 系列  ·  不含网关、中间件及可选解析服务',1329,815,21,fill=MUTED,center=True)
# Main application panel
box(360,861,920,368,tint(COLORS[2],.025),tint(COLORS[2],.22),20)
text('业务主应用 · 模块化单体',820,873,27,True,center=True)
items=[('用户与权限',['部门树、角色、密级'],0),('文档管理',['分类、标签、版本、审核'],2),('统一检索',['BM25 + 向量 + rerank'],1),('文件管理',['上传、下载、授权预览'],0),('基础与审计',['配置、通知、操作审计'],2),('统计分析',['用量、趋势、分布'],4)]
for idx,(title,lines,c) in enumerate(items):
    card(380+(idx%3)*294,928+(idx//3)*140,274,120,title,lines,c)
# Worker panel
box(1304,861,485,368,tint(COLORS[0],.025),tint(COLORS[0],.22),20)
text('解析索引 Worker',1546,873,27,True,center=True)
card(1322,928,449,120,'文档解析与转换',['统一第三方解析；具体服务待选','OCR / Office 转 PDF 按需接入¹'],0)
card(1322,1068,449,141,'异步索引流水线',['清洗 → 分块 → 嵌入 → 双索引','按版本发布、幂等、重试、任务回收'],2)
# AI panel
box(1813,861,485,368,tint(COLORS[4],.025),tint(COLORS[4],.22),20)
text('AI 服务',2055,873,27,True,center=True)
card(1831,928,449,120,'RAG 与文档智能',['授权检索、rerank、引用、SSE','摘要、扩写、优化按需启用'],4)
card(1831,1068,449,141,'模型适配与治理',['调用授权检索接口，不绕过 ACL','超时、并发、按部门配额、脱敏'],1)
box(380,1245,1898,49,'#f5f7fa','#e5eaf0',14)
text('公共能力：统一授权 · 异常处理 · 审计与追踪 · 任务状态与幂等 · 备份恢复 · 质量评测',1329,1255,22,fill=MUTED,center=True)

layer(5,1342,350,'底座能力层',['数据、存储与任务','模型与检索能力'])
for x,w,title in [(360,746,'数据存储与检索'),(1130,604,'对象存储与异步任务'),(1758,540,'模型能力（公有云 API）')]:
    box(x,1361,w,315,'#fbfcfd','#e5eaf0',20)
    text(title,x+w/2,1375,26,True,center=True)
card(378,1428,710,128,'PostgreSQL + ParadeDB + pgvector',['元数据、ACL、密级、版本、分块、向量与 BM25 索引','自部署 · pg_search 提供真 BM25 · 中文分词待验证'],0)
card(378,1570,710,90,'BM25 + 向量两路召回 → RRF 融合 → rerank 重排',['权限与密级下推到检索 · 空间级过滤利于召回'],4)
card(1148,1428,568,104,'S3 兼容对象存储',['MinIO / 其他兼容服务 · 原件与预览产物'],2)
card(1148,1548,568,112,'异步任务机制  [待定]',['PG 持久任务队列 / RabbitMQ','二选一，不同时引入'],1)
card(1776,1428,504,104,'嵌入 / 对话 / rerank  API',['✓ 合规通过 · 通义千问 / DeepSeek 等²'],4)
card(1776,1548,504,112,'Redis  [按需]',['缓存、会话辅助与限流','缓存按权限隔离'],0)

text('本轮落定：合规通过（可调公有云 embedding / chat / rerank）· 检索引擎选定 ParadeDB · 权限模型定型（空间为主 + 文档例外 + 部门树 + 密级）。',72,1717,21,True)
text('已确认移除：MongoDB、Neo4j / 知识图谱、音视频转码与 HLS；对象存储不绑定 RustFS。',72,1751,20,fill=MUTED)
text('¹ 复杂解析 / OCR / Office 预览需样本验证，可能增加部署单元。  ² 首期文档例外只扩权不收紧；显式拒绝与对外分享外链列入二期。',72,1783,20,fill=MUTED)
text('设计稿 · 更新 2026-09-30  |  ParadeDB 中文分词质量、容量与权限正确性尚待实测  |  详细取舍见《架构决策记录.md》',72,1815,20,fill=MUTED)

im.save(ROOT/'架构图-v3.png',dpi=(240,240))
im.save(ROOT/'架构图-v3.jpg',quality=95,subsampling=0,dpi=(240,240))
im.resize((1800,1410),Image.Resampling.LANCZOS).save(ROOT/'架构图-v3-预览.png')
print('Rendered PNG and JPG:', im.size, '-> preview 1800 x 1410')
