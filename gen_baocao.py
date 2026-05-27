
# -*- coding: utf-8 -*-
"""
Script tạo báo cáo bài tập lớn P2PChat dạng .docx
Nhóm 13 - Lớp 01 - Các hệ thống phân tán
"""

from docx import Document
from docx.shared import Pt, Cm, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_ALIGN_VERTICAL
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
import copy

# ============================================================
# HELPER FUNCTIONS
# ============================================================

def set_cell_bg(cell, hex_color):
    """Set background color of a table cell."""
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hex_color)
    tcPr.append(shd)

def set_table_border(table):
    """Add borders to all cells in a table."""
    tbl = table._tbl
    tblPr = tbl.tblPr
    if tblPr is None:
        tblPr = OxmlElement('w:tblPr')
        tbl.insert(0, tblPr)
    tblBorders = OxmlElement('w:tblBorders')
    for border_name in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']:
        border = OxmlElement(f'w:{border_name}')
        border.set(qn('w:val'), 'single')
        border.set(qn('w:sz'), '4')
        border.set(qn('w:space'), '0')
        border.set(qn('w:color'), 'BFBFBF')
        tblBorders.append(border)
    tblPr.append(tblBorders)

def add_paragraph_with_style(doc, text, style_name='Normal', bold=False, 
                               italic=False, font_size=None, color=None,
                               alignment=WD_ALIGN_PARAGRAPH.LEFT, space_before=None, space_after=None):
    p = doc.add_paragraph(style=style_name)
    p.alignment = alignment
    if space_before is not None:
        p.paragraph_format.space_before = Pt(space_before)
    if space_after is not None:
        p.paragraph_format.space_after = Pt(space_after)
    run = p.add_run(text)
    run.bold = bold
    run.italic = italic
    if font_size:
        run.font.size = Pt(font_size)
    if color:
        run.font.color.rgb = RGBColor(*color)
    return p

def add_heading(doc, text, level):
    """Add a styled heading."""
    p = doc.add_heading(text, level=level)
    p.paragraph_format.space_before = Pt(12 if level == 1 else 8)
    p.paragraph_format.space_after = Pt(6)
    return p

def add_bullet(doc, text, level=0):
    """Add a bullet point paragraph."""
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0 + level * 0.5)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(text)
    run.font.size = Pt(12)
    return p

def add_numbered(doc, text, num):
    """Add numbered paragraph."""
    p = doc.add_paragraph(style='List Number')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(text)
    run.font.size = Pt(12)
    return p

def add_body(doc, text, bold_parts=None):
    """Add body paragraph with optional bold parts."""
    p = doc.add_paragraph(style='Normal')
    p.paragraph_format.space_after = Pt(6)
    p.paragraph_format.first_line_indent = Cm(0.75)
    if bold_parts is None:
        run = p.add_run(text)
        run.font.size = Pt(12)
    else:
        # bold_parts: list of (text, is_bold)
        for part, is_bold in bold_parts:
            run = p.add_run(part)
            run.bold = is_bold
            run.font.size = Pt(12)
    return p

def add_code_block(doc, code_text):
    """Add a code block paragraph."""
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(1.5)
    p.paragraph_format.right_indent = Cm(0.5)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(4)
    # Light gray shading
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), 'F2F2F2')
    pPr.append(shd)
    run = p.add_run(code_text)
    run.font.name = 'Courier New'
    run.font.size = Pt(9)
    return p

def add_note_box(doc, text):
    """Add a note/callout box."""
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.right_indent = Cm(0.5)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(4)
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), 'EBF3FB')
    pPr.append(shd)
    run = p.add_run('📌  Lưu ý: ')
    run.bold = True
    run.font.size = Pt(11)
    run.font.color.rgb = RGBColor(0x1A, 0x53, 0x9A)
    run2 = p.add_run(text)
    run2.font.size = Pt(11)
    run2.font.color.rgb = RGBColor(0x1A, 0x53, 0x9A)
    return p

def add_table(doc, headers, rows, col_widths=None, header_bg='1F4E79'):
    """Add a styled table."""
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    set_table_border(table)
    # Header row
    hdr_cells = table.rows[0].cells
    for i, h in enumerate(headers):
        set_cell_bg(hdr_cells[i], header_bg)
        hdr_cells[i].vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        p = hdr_cells[i].paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        run = p.add_run(h)
        run.bold = True
        run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
        run.font.size = Pt(11)
    # Data rows
    for ri, row_data in enumerate(rows):
        row_cells = table.rows[ri + 1].cells
        bg = 'F7FBFF' if ri % 2 == 0 else 'FFFFFF'
        for ci, cell_text in enumerate(row_data):
            set_cell_bg(row_cells[ci], bg)
            row_cells[ci].vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            p = row_cells[ci].paragraphs[0]
            # Check if it's centered (numeric-ish or short label)
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            run = p.add_run(str(cell_text))
            run.font.size = Pt(10.5)
    if col_widths:
        for ri_idx, row in enumerate(table.rows):
            for ci_idx, cell in enumerate(row.cells):
                if ci_idx < len(col_widths):
                    cell.width = Cm(col_widths[ci_idx])
    return table

def add_section_divider(doc):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run('─' * 72)
    run.font.color.rgb = RGBColor(0xCC, 0xCC, 0xCC)
    run.font.size = Pt(8)

# ============================================================
# DOCUMENT SETUP
# ============================================================

doc = Document()

# Page margins
section = doc.sections[0]
section.top_margin = Cm(2.5)
section.bottom_margin = Cm(2.5)
section.left_margin = Cm(3.0)
section.right_margin = Cm(2.0)
section.page_width = Cm(21)
section.page_height = Cm(29.7)

# Default style
style = doc.styles['Normal']
style.font.name = 'Times New Roman'
style.font.size = Pt(12)
style.paragraph_format.line_spacing = Pt(18)

# Heading styles
for i in range(1, 5):
    hs = doc.styles[f'Heading {i}']
    hs.font.name = 'Times New Roman'
    hs.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)
    if i == 1:
        hs.font.size = Pt(16)
        hs.font.bold = True
    elif i == 2:
        hs.font.size = Pt(14)
        hs.font.bold = True
    elif i == 3:
        hs.font.size = Pt(13)
        hs.font.bold = True
    elif i == 4:
        hs.font.size = Pt(12)
        hs.font.bold = True

# ============================================================
# TRANG BÌA
# ============================================================

doc.add_paragraph()
doc.add_paragraph()

# Trường - Khoa
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run('TRƯỜNG ĐẠI HỌC BÁCH KHOA HÀ NỘI')
r.bold = True
r.font.size = Pt(14)
r.font.name = 'Times New Roman'

p2 = doc.add_paragraph()
p2.alignment = WD_ALIGN_PARAGRAPH.CENTER
r2 = p2.add_run('KHOA CÔNG NGHỆ THÔNG TIN')
r2.bold = True
r2.font.size = Pt(14)
r2.font.name = 'Times New Roman'

doc.add_paragraph()
doc.add_paragraph()
doc.add_paragraph()

# Tiêu đề báo cáo
p3 = doc.add_paragraph()
p3.alignment = WD_ALIGN_PARAGRAPH.CENTER
r3 = p3.add_run('BÁO CÁO BÀI TẬP LỚN')
r3.bold = True
r3.font.size = Pt(20)
r3.font.name = 'Times New Roman'
r3.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)

doc.add_paragraph()

p4 = doc.add_paragraph()
p4.alignment = WD_ALIGN_PARAGRAPH.CENTER
r4 = p4.add_run('MÔN HỌC: CÁC HỆ THỐNG PHÂN TÁN')
r4.bold = True
r4.font.size = Pt(14)
r4.font.name = 'Times New Roman'
r4.font.color.rgb = RGBColor(0x27, 0x6D, 0xB4)

doc.add_paragraph()

# Tên đề tài
p5 = doc.add_paragraph()
p5.alignment = WD_ALIGN_PARAGRAPH.CENTER
r5a = p5.add_run('Đề tài: ')
r5a.font.size = Pt(15)
r5a.font.name = 'Times New Roman'
r5b = p5.add_run('XÂY DỰNG HỆ THỐNG CHAT NGANG HÀNG (P2P CHAT)')
r5b.bold = True
r5b.font.size = Pt(15)
r5b.font.name = 'Times New Roman'
r5b.font.color.rgb = RGBColor(0xC0, 0x00, 0x00)

doc.add_paragraph()
doc.add_paragraph()
doc.add_paragraph()
doc.add_paragraph()

# Nhóm và lớp
p6 = doc.add_paragraph()
p6.alignment = WD_ALIGN_PARAGRAPH.CENTER
r6 = p6.add_run('Nhóm thực hiện: Nhóm 13 — Lớp 01')
r6.bold = True
r6.font.size = Pt(13)
r6.font.name = 'Times New Roman'

p7 = doc.add_paragraph()
p7.alignment = WD_ALIGN_PARAGRAPH.CENTER
r7 = p7.add_run('Học phần: Các hệ thống phân tán')
r7.font.size = Pt(13)
r7.font.name = 'Times New Roman'

doc.add_paragraph()
doc.add_paragraph()

p8 = doc.add_paragraph()
p8.alignment = WD_ALIGN_PARAGRAPH.CENTER
r8 = p8.add_run('Hà Nội, 2026')
r8.bold = True
r8.font.size = Pt(13)
r8.font.name = 'Times New Roman'

# Page break sau trang bìa
doc.add_page_break()

# ============================================================
# LỜI NÓI ĐẦU
# ============================================================
add_heading(doc, 'LỜI NÓI ĐẦU', 1)

body_loinoidau = [
    'Trong bối cảnh công nghệ thông tin phát triển mạnh mẽ, nhu cầu giao tiếp trực tuyến ngày càng trở nên thiết yếu. Hầu hết các ứng dụng nhắn tin hiện nay như Facebook Messenger, Zalo hay Telegram đều hoạt động theo mô hình client-server truyền thống — nơi mọi tin nhắn phải đi qua máy chủ trung tâm của nhà cung cấp dịch vụ. Mô hình này tuy phổ biến nhưng tiềm ẩn nhiều hạn chế về quyền riêng tư, phụ thuộc hạ tầng và khả năng chịu lỗi.',
    'Xuất phát từ những vấn đề thực tế trên, nhóm chúng em đã lựa chọn và thực hiện đề tài "Xây dựng hệ thống chat ngang hàng (P2P Chat)" trong khuôn khổ môn học Các hệ thống phân tán. Mục tiêu của đề tài là thiết kế và xây dựng một hệ thống chat phi tập trung, nơi người dùng giao tiếp trực tiếp với nhau mà không cần phụ thuộc vào một máy chủ trung tâm cho việc truyền tải tin nhắn.',
    'Hệ thống P2PChat được xây dựng với đầy đủ các tính năng: nhắn tin trực tiếp peer-to-peer, chat nhóm với thuật toán HRW phân tán, lưu trữ tin nhắn offline thông qua Mailbox Server, chuyển file trực tiếp, mã hóa đầu cuối, và giao diện web thân thiện. Hệ thống có khả năng triển khai thực tế qua Docker và kết nối Internet thông qua Tailscale VPN.',
    'Báo cáo này trình bày toàn bộ quá trình thiết kế, triển khai và đánh giá hệ thống P2PChat — từ cơ sở lý thuyết, phân tích và thiết kế, đến quá trình cài đặt và thử nghiệm thực tế. Chúng em xin chân thành cảm ơn thầy/cô hướng dẫn đã tạo điều kiện để nhóm hoàn thành đề tài này.',
]
for txt in body_loinoidau:
    add_body(doc, txt)
doc.add_paragraph()
p_sign = doc.add_paragraph()
p_sign.alignment = WD_ALIGN_PARAGRAPH.RIGHT
r_sign = p_sign.add_run('Nhóm 13, Hà Nội tháng 5 năm 2026')
r_sign.italic = True
r_sign.font.size = Pt(12)

doc.add_page_break()

# ============================================================
# MỤC LỤC (tĩnh)
# ============================================================
add_heading(doc, 'MỤC LỤC', 1)

muc_luc = [
    ('Lời nói đầu', '2'),
    ('Chương I. Giới thiệu về hệ thống Chat P2P', '4'),
    ('    1.1. Đặt vấn đề', '4'),
    ('    1.2. Mục tiêu đề tài', '5'),
    ('    1.3. Phạm vi và phương pháp thực hiện', '6'),
    ('Chương II. Cơ sở lý thuyết', '8'),
    ('    2.1. Mạng ngang hàng (Peer-to-Peer)', '8'),
    ('    2.2. Giao thức TCP trong truyền thông P2P', '9'),
    ('    2.3. Cơ chế đăng ký và khám phá peer', '10'),
    ('    2.4. Heartbeat và phát hiện peer chết', '11'),
    ('    2.5. Cơ chế Store-and-Forward và Mailbox Server', '12'),
    ('    2.6. Chat nhóm DHT-Lite và thuật toán HRW', '13'),
    ('    2.7. Chuyển file trong mạng P2P', '14'),
    ('    2.8. Mã hóa đầu cuối (E2EE)', '15'),
    ('Chương III. Phân tích và Thiết kế hệ thống', '16'),
    ('    3.1. Kiến trúc tổng thể', '16'),
    ('    3.2. Giao thức trao đổi thông điệp', '18'),
    ('    3.3. Cơ chế Peer Discovery', '22'),
    ('    3.4. Cơ chế chat nhóm DHT-Lite', '24'),
    ('    3.5. Cơ chế truyền file', '27'),
    ('    3.6. Cơ chế Store-and-Forward', '29'),
    ('    3.7. Thiết kế cơ sở dữ liệu', '31'),
    ('Chương IV. Triển khai hệ thống', '33'),
    ('    4.1. Môi trường và công nghệ sử dụng', '33'),
    ('    4.2. Cấu trúc mã nguồn', '34'),
    ('    4.3. Triển khai Bootstrap Server', '35'),
    ('    4.4. Triển khai Peer Node', '37'),
    ('    4.5. Triển khai tính năng chat trực tiếp', '39'),
    ('    4.6. Triển khai chat nhóm', '41'),
    ('    4.7. Triển khai truyền file', '43'),
    ('    4.8. Triển khai Mailbox Server và Outbox Engine', '44'),
    ('    4.9. Giao diện Web và đóng gói Docker', '46'),
    ('Chương V. Thử nghiệm hệ thống và Đánh giá', '48'),
    ('    5.1. Môi trường thử nghiệm', '48'),
    ('    5.2. Các kịch bản thử nghiệm', '49'),
    ('    5.3. Kết quả thử nghiệm', '53'),
    ('    5.4. Đánh giá tổng thể', '55'),
    ('Chương VI. Kết luận', '58'),
    ('    6.1. Tổng kết kết quả đạt được', '58'),
    ('    6.2. Các vấn đề đã giải quyết', '59'),
    ('    6.3. Hướng phát triển tiếp theo', '60'),
    ('    6.4. Bài học kinh nghiệm', '61'),
    ('Tài liệu tham khảo', '62'),
]

for item, page in muc_luc:
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(2)
    r1 = p.add_run(item)
    r1.font.size = Pt(12)
    r1.font.name = 'Times New Roman'
    if not item.startswith('    '):
        r1.bold = True

doc.add_page_break()

# ============================================================
# CHƯƠNG I
# ============================================================
add_heading(doc, 'CHƯƠNG I. GIỚI THIỆU VỀ HỆ THỐNG CHAT P2P', 1)

# 1.1
add_heading(doc, '1.1. Đặt vấn đề', 2)
add_body(doc, 'Nhắn tin trực tuyến là nhu cầu thiết yếu trong thời đại số. Các ứng dụng chat phổ biến hiện nay như Facebook Messenger, Zalo, Telegram đều hoạt động theo mô hình client-server — tức là mọi tin nhắn đều phải đi qua máy chủ trung tâm của nhà cung cấp dịch vụ. Mô hình này có một số hạn chế đáng chú ý:')

bullet_items = [
    ('Phụ thuộc vào server trung tâm: ', 'Nếu server gặp sự cố hoặc bị tấn công, toàn bộ hệ thống bị gián đoạn. Không có server trung tâm, người dùng không thể liên lạc.'),
    ('Vấn đề quyền riêng tư: ', 'Nhà cung cấp dịch vụ có thể đọc, lưu trữ và phân tích nội dung tin nhắn của người dùng.'),
    ('Chi phí vận hành cao: ', 'Cần duy trì hạ tầng server quy mô lớn để phục vụ hàng triệu người dùng đồng thời.'),
    ('Khó khăn khi mở rộng: ', 'Băng thông và khả năng xử lý của server trung tâm là điểm thắt cổ chai khi lượng người dùng tăng đột biến.'),
]
for bold_part, normal_part in bullet_items:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    rb = p.add_run(bold_part)
    rb.bold = True
    rb.font.size = Pt(12)
    rn = p.add_run(normal_part)
    rn.font.size = Pt(12)

add_body(doc, 'Để khắc phục các hạn chế trên, mô hình peer-to-peer (P2P) được đề xuất và ứng dụng rộng rãi trong nhiều lĩnh vực như chia sẻ file (BitTorrent), giao tiếp VoIP (Skype phiên bản cũ). Trong mô hình P2P, mỗi người dùng (peer) vừa là client gửi tin nhắn, vừa là server nhận tin nhắn từ người khác. Dữ liệu được truyền trực tiếp giữa các peer mà không phải qua bất kỳ máy chủ trung tâm nào, mang lại tính phân cấp, khả năng chịu lỗi cao hơn và giảm phụ thuộc vào hạ tầng tập trung.')
add_body(doc, 'Tuy nhiên, xây dựng một hệ thống chat P2P hoàn chỉnh đặt ra nhiều thách thức thực tế: làm sao để peer mới biết các peer khác trong mạng, làm sao để gửi tin nhắn khi người nhận đang offline, làm sao để quản lý nhóm chat hiệu quả, và làm sao để truyền file trực tiếp giữa các peer. Hệ thống P2PChat được xây dựng nhằm giải quyết các thách thức này.')

# 1.2
add_heading(doc, '1.2. Mục tiêu đề tài', 2)
add_heading(doc, '1.2.1. Mục tiêu tổng quát', 3)
add_body(doc, 'Xây dựng một hệ thống chat ngang hàng (peer-to-peer) hoàn chỉnh, cho phép người dùng giao tiếp trực tiếp với nhau qua mạng Internet mà không phụ thuộc vào máy chủ trung tâm cho việc truyền tin nhắn, đảm bảo tính riêng tư, độ tin cậy và khả năng hoạt động ngay cả khi một số peer trong mạng bị ngắt kết nối.')

add_heading(doc, '1.2.2. Mục tiêu cụ thể', 3)
goals = [
    'Xây dựng Bootstrap Server làm điểm vào mạng, cho phép peer mới đăng ký, khám phá các peer đang hoạt động và theo dõi trạng thái online/offline qua cơ chế heartbeat.',
    'Triển khai cơ chế nhắn tin trực tiếp P2P qua giao thức TCP, đảm bảo tin nhắn được gửi trực tiếp từ peer này đến peer khác với cơ chế xác nhận (ACK) và retry khi thất bại.',
    'Hỗ trợ tin nhắn offline thông qua Mailbox Server, cho phép lưu trữ tin nhắn tạm thời khi người nhận không online và tự động gửi lại khi người nhận trở lại hoạt động.',
    'Xây dựng tính năng chat nhóm dựa trên mô hình DHT-Lite với thuật toán HRW (Highest Random Weight) để chọn coordinator, đảm bảo quản lý nhóm hiệu quả.',
    'Cung cấp tính năng broadcast cho phép gửi tin nhắn đến tất cả peer đang online trong mạng một cách nhanh chóng.',
    'Hỗ trợ chuyển file trực tiếp giữa các peer với cơ chế chunk, kiểm tra SHA-256 và khả năng resume sau gián đoạn kết nối.',
    'Triển khai mã hóa đầu cuối (E2EE) cho nội dung tin nhắn, đảm bảo chỉ người gửi và người nhận có thể đọc được nội dung.',
    'Xây dựng giao diện web (Web UI) thân thiện với người dùng, hỗ trợ realtime qua WebSocket.',
    'Triển khai hệ thống bằng Docker để dễ dàng khởi chạy, quản lý và mở rộng nhiều peer cùng lúc.',
    'Hỗ trợ kết nối qua Internet bằng Tailscale VPN, cho phép các peer ở các mạng nội bộ khác nhau giao tiếp P2P trực tiếp.',
]
for i, g in enumerate(goals):
    p = doc.add_paragraph(style='List Number')
    p.paragraph_format.left_indent = Cm(1.2)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(g)
    run.font.size = Pt(12)

# 1.3
add_heading(doc, '1.3. Phạm vi và phương pháp thực hiện', 2)
add_heading(doc, '1.3.1. Phạm vi đề tài', 3)

p_ph = doc.add_paragraph()
p_ph.paragraph_format.space_after = Pt(4)
rb = p_ph.add_run('Phạm vi không gian: ')
rb.bold = True
rb.font.size = Pt(12)
rn = p_ph.add_run('Đề tài được thực hiện trên môi trường mạng cục bộ (LAN), có thể mở rộng ra mạng Internet thông qua Tailscale VPN. Hệ thống được triển khai và thử nghiệm trên các máy tính cá nhân sử dụng hệ điều hành Windows và Linux (WSL2), sử dụng Docker để container hóa các thành phần. Các peer có thể hoạt động trên cùng một mạng LAN hoặc ở các mạng LAN khác nhau thông qua mạng riêng ảo Tailscale.')
rn.font.size = Pt(12)

p_pt = doc.add_paragraph()
p_pt.paragraph_format.space_after = Pt(4)
rb2 = p_pt.add_run('Phạm vi thời gian: ')
rb2.bold = True
rb2.font.size = Pt(12)
rn2 = p_pt.add_run('Đề tài được thực hiện trong khuôn khổ môn học Các hệ thống phân tán. Hệ thống được xây dựng và kiểm thử trong học kỳ, đảm bảo các chức năng cốt lõi hoạt động ổn định.')
rn2.font.size = Pt(12)

add_heading(doc, '1.3.2. Phương pháp thực hiện', 3)
add_body(doc, 'Đề tài sử dụng phương pháp phân tích top-down kết hợp bottom-up: phân tích yêu cầu từ góc nhìn người dùng, phân rã thành các thành phần kiến trúc, sau đó xây dựng từng thành phần độc lập và tích hợp lại thành hệ thống hoàn chỉnh. Thiết kế tuân theo nguyên tắc modular — mỗi thành phần có nhiệm vụ rõ ràng, giao tiếp qua giao thức đã định nghĩa, dễ dàng thay thế và mở rộng.')

add_body(doc, 'Công cụ và môi trường phát triển gồm: Java 17 + Maven cho backend (Bootstrap, Mailbox, Peer Node), React 18 + npm cho frontend web, Docker để container hóa, Tailscale để tạo mạng VPN, SQLite cho lưu trữ cục bộ và Git để quản lý mã nguồn.')

p_quy = doc.add_paragraph()
p_quy.paragraph_format.space_before = Pt(4)
p_quy.paragraph_format.space_after = Pt(4)
p_quy.paragraph_format.first_line_indent = Cm(0.75)
rb_quy = p_quy.add_run('Quy trình phát triển bao gồm: ')
rb_quy.bold = True
rb_quy.font.size = Pt(12)
rn_quy = p_quy.add_run('(1) Nghiên cứu lý thuyết; (2) Thiết kế kiến trúc và giao thức; (3) Xây dựng từng thành phần; (4) Kiểm thử và tích hợp; (5) Triển khai và đánh giá; (6) Viết báo cáo.')
rn_quy.font.size = Pt(12)

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# CHƯƠNG II
# ============================================================
add_heading(doc, 'CHƯƠNG II. CƠ SỞ LÝ THUYẾT CỦA HỆ THỐNG CHAT P2P', 1)

# 2.1
add_heading(doc, '2.1. Mạng ngang hàng (Peer-to-Peer Network)', 2)
add_heading(doc, '2.1.1. Khái niệm', 3)
add_body(doc, 'Mạng ngang hàng (Peer-to-Peer — P2P) là mô hình kiến trúc phân tán trong đó mỗi nút (peer) vừa đóng vai trò client gửi yêu cầu, vừa đóng vai trò server phục vụ yêu cầu từ các nút khác. Không có máy chủ trung tâm duy nhất điều khiển toàn bộ hệ thống. So với mô hình client-server truyền thống, P2P có các đặc điểm nổi bật:')

features = [
    ('Phân cấp: ', 'Mỗi peer có quyền hạn ngang nhau, không có điểm thất bại đơn lẻ (single point of failure) duy nhất.'),
    ('Khả năng mở rộng tự nhiên: ', 'Khi thêm peer mới, cả tài nguyên lẫn năng lực xử lý của mạng đều tăng.'),
    ('Khả năng chịu lỗi: ', 'Dữ liệu được sao chép trên nhiều peer; một peer ngắt kết nối không ảnh hưởng đến toàn mạng.'),
    ('Tiết kiệm chi phí hạ tầng: ', 'Không cần đầu tư máy chủ tập trung quy mô lớn.'),
]
for bold_part, normal_part in features:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    rb = p.add_run(bold_part)
    rb.bold = True
    rb.font.size = Pt(12)
    rn = p.add_run(normal_part)
    rn.font.size = Pt(12)

add_heading(doc, '2.1.2. Phân loại mạng P2P', 3)
add_table(doc,
    ['Loại', 'Mô tả', 'Ứng dụng điển hình'],
    [
        ['Pure P2P', 'Không có server trung tâm; peer tìm nhau qua giao thức phân tán (gossip, DHT).', 'Gnutella, BitTorrent'],
        ['Hybrid P2P', 'Có server hỗ trợ một số chức năng nhưng dữ liệu chat truyền trực tiếp P2P.', 'Skype (legacy), hệ thống này'],
        ['Structured P2P', 'Sử dụng DHT để ánh xạ khóa → peer chịu trách nhiệm.', 'Chord, Kademlia, Tapestry'],
        ['Unstructured P2P', 'Peer kết nối ngẫu nhiên; tìm kiếm bằng flooding hoặc random walk.', 'Gnutella'],
    ],
    col_widths=[3.5, 8.0, 5.0]
)

doc.add_paragraph()
add_note_box(doc, 'Hệ thống P2PChat thiên về mô hình Hybrid P2P: Bootstrap Server đóng vai trò điểm vào mạng, nhưng toàn bộ dữ liệu chat được truyền trực tiếp giữa các peer qua TCP.')

add_heading(doc, '2.1.3. So sánh Client-Server và P2P', 3)
add_table(doc,
    ['Tiêu chí', 'Client-Server', 'P2P'],
    [
        ['Kiến trúc', 'Tập trung', 'Phân tán'],
        ['Máy chủ trung tâm', 'Bắt buộc', 'Không bắt buộc (hybrid)'],
        ['Điểm thất bại', '1 máy chủ', 'Nhiều điểm — bền bỉ hơn'],
        ['Chi phí vận hành', 'Cao (cần server mạnh)', 'Thấp (tài nguyên phân tán)'],
        ['Bảo mật tin nhắn', 'Server kiểm soát hoàn toàn', 'Peer tự quản lý E2EE'],
        ['Khả năng mở rộng', 'Giới hạn băng thông server', 'Tăng theo số peer'],
    ],
    col_widths=[5.5, 5.5, 5.5]
)

# 2.2
doc.add_paragraph()
add_heading(doc, '2.2. Giao thức TCP trong truyền thông P2P', 2)
add_heading(doc, '2.2.1. TCP Socket', 3)
add_body(doc, 'TCP (Transmission Control Protocol) là giao thức truyền tin hướng kết nối, đáng tin cậy, đảm bảo thứ tự và toàn vẹn dữ liệu. Trong hệ thống P2P, mỗi peer mở một ServerSocket để lắng nghe kết nối đến và sử dụng Socket để kết nối đến peer khác. Ưu điểm của TCP cho chat P2P: cơ chế ACK đảm bảo tin nhắn được nhận; hỗ trợ streaming liên tục (phù hợp cho file transfer); kiến trúc đơn giản và dễ triển khai.')

add_heading(doc, '2.2.2. Định dạng message trong hệ thống', 3)
add_body(doc, 'Mỗi message trong hệ thống được truyền dưới dạng một dòng JSON theo quy tắc newline-delimited (mỗi message kết thúc bằng ký tự xuống dòng \\n). Cấu trúc chung của một message gồm:')
add_table(doc,
    ['Trường', 'Kiểu dữ liệu', 'Mô tả'],
    [
        ['type', 'String', 'Loại message (DIRECT_MESSAGE, HEARTBEAT, REGISTER, ...)'],
        ['messageId', 'UUID v4', 'Định danh duy nhất mỗi message'],
        ['sender', 'String', 'Username người gửi'],
        ['receiver', 'String', 'Username người nhận (null khi broadcast)'],
        ['groupId', 'String', 'Tên nhóm nếu là group message'],
        ['content', 'String', 'Nội dung tin nhắn, có thể được mã hóa E2EE'],
        ['timestamp', 'Long', 'Thời gian gửi dạng epoch milliseconds'],
    ],
    col_widths=[3.5, 3.0, 10.0]
)

add_heading(doc, '2.2.3. Cổng giao tiếp (Ports)', 3)
add_table(doc,
    ['Cổng', 'Vai trò'],
    [
        ['webPort', 'HTTP/WebSocket cho Web UI (Javalin)'],
        ['peerPort = webPort + 1000', 'Peer TCP server nhận tin nhắn P2P'],
        ['filePort = peerPort + 1000', 'TCP server nhận chunk file transfer'],
    ],
    col_widths=[6.0, 10.5]
)

# 2.3
doc.add_paragraph()
add_heading(doc, '2.3. Cơ chế đăng ký và khám phá peer (Bootstrap Server)', 2)
add_body(doc, 'Trong mạng P2P thuần túy, mỗi peer cần biết ít nhất một peer khác để tham gia mạng. Bootstrap Server giải quyết vấn đề "cold start" này bằng cách đóng vai trò điểm vào duy nhất cho tất cả peer. Chức năng chính gồm: Đăng ký (Register) — peer gửi thông tin để tham gia mạng; Peer Discovery — cung cấp danh sách peer online; Heartbeat — theo dõi trạng thái sống/chết của mỗi peer; Điều phối mailbox — trả endpoint Mailbox Server khi có yêu cầu.')

add_note_box(doc, 'Bootstrap Server KHÔNG truyền nội dung chat. Mọi tin nhắn chat được gửi trực tiếp peer-to-peer qua TCP. Bootstrap chỉ quản lý metadata và điều phối kết nối.')

# 2.4
add_heading(doc, '2.4. Heartbeat và phát hiện peer chết (Dead-Peer Detection)', 2)
add_body(doc, 'Heartbeat là cơ chế keep-alive giúp Bootstrap Server theo dõi trạng thái sống của mỗi peer. Peer gửi HEARTBEAT mỗi 5 giây tới Bootstrap. Peer không heartbeat trong 45 giây (9 chu kỳ liên tiếp) sẽ bị coi là dead và bị xóa khỏi registry. Bootstrap Server broadcast PEER_LEAVE đến tất cả peer còn lại để thông báo sự kiện này.')

add_table(doc,
    ['Hành vi', 'Graceful (PEER_LEAVE)', 'Crash (timeout)'],
    [
        ['Peer gửi thông báo', 'Có', 'Không'],
        ['Bootstrap xóa ngay', 'Có', 'Không (chờ timeout)'],
        ['Broadcast PEER_LEAVE', 'Ngay lập tức', 'Sau 45 giây'],
        ['Peer khác phát hiện', '< 1 giây', '5 – 45 giây'],
    ],
    col_widths=[5.5, 5.0, 6.0]
)

# 2.5
doc.add_paragraph()
add_heading(doc, '2.5. Cơ chế Store-and-Forward và Mailbox Server', 2)
add_body(doc, 'Khi người nhận không online, gửi trực tiếp TCP sẽ thất bại. Mailbox Server là một TCP server độc lập, sử dụng SQLite làm cơ sở dữ liệu, đóng vai trò kho lưu trữ tạm thời cho các tin nhắn offline. Luồng hoạt động chính: Sender gửi DIRECT_MESSAGE → TCP thất bại sau 3 retry → Sender gửi STORE_MESSAGE đến Mailbox → Mailbox lưu vào database và trả STORE_ACK → Khi Receiver online, gửi PULL_MESSAGES → Mailbox trả các tin nhắn chờ → Receiver gửi DELIVERY_ACK.')

add_body(doc, 'Mailbox Server có TTL 7 ngày — tin nhắn hết hạn sau 7 ngày nếu không được nhận. Payload được mã hóa E2EE nên Mailbox chỉ lưu blob mà không đọc được nội dung. Group message được theo dõi delivery ack per-member để đảm bảo mỗi thành viên nhận đủ tin nhắn.')

# 2.6
add_heading(doc, '2.6. Chat nhóm DHT-Lite và thuật toán HRW', 2)
add_body(doc, 'DHT (Distributed Hash Table) là cấu trúc dữ liệu phân tán ánh xạ khóa → giá trị. DHT-Lite là phiên bản đơn giản hóa phù hợp cho hệ thống chat nhóm quy mô nhỏ/vừa. Mỗi nhóm có K=3 coordinator được chọn bằng thuật toán HRW (Highest Random Weight — Rendezvous Hashing).')
add_body(doc, 'Công thức HRW: hash_i = SHA256(groupId + peerId). K peer có hash_i lớn nhất được chọn làm coordinator. Tính chất quan trọng của HRW: nhất quán (khi thành viên thay đổi, chỉ một số nhỏ coordinator thay đổi); phân bố đều; không cần trao đổi message để bầu coordinator.')

# 2.7
add_heading(doc, '2.7. Chuyển file trong mạng P2P', 2)
add_body(doc, 'File được chia thành các chunk 64 KB và truyền tuần tự qua kết nối TCP riêng (file port). Quy trình: Sender tính SHA-256 hash của toàn bộ file → gửi FILE_OFFER chứa thông tin file → Receiver chấp nhận FILE_ACCEPT hoặc từ chối FILE_REJECT → Receiver kết nối TCP đến filePort của Sender → tải file theo chunk 64 KB → xác minh SHA-256 → FILE_DONE và FILE_ACK.')
add_body(doc, 'Hệ thống hỗ trợ resume sau gián đoạn: lưu checkpoint chunk cuối đã nhận; nếu kết nối bị gián đoạn, receiver gửi FILE_RESUME để tiếp tục từ chunk checkpoint. Giới hạn kỹ thuật: tối đa 100 MB/file, chunk size 64 KB.')

# 2.8
add_heading(doc, '2.8. Mã hóa đầu cuối (End-to-End Encryption — E2EE)', 2)
add_body(doc, 'E2EE đảm bảo chỉ người gửi và người nhận có thể đọc nội dung tin nhắn. Ngay cả Mailbox Server cũng không thể giải mã vì payload được mã hóa trước khi gửi. Cơ chế: mỗi peer có một cặp khóa RSA 2048-bit (public key + private key). Public key được đăng ký với Bootstrap Server. Khi gửi tin nhắn, sender mã hóa payload bằng public key của receiver. Receiver giải mã bằng private key của mình.')

doc.add_paragraph()
add_table(doc,
    ['Công nghệ', 'Phiên bản', 'Mục đích'],
    [
        ['Java', '17+', 'Backend TCP, HTTP, storage logic'],
        ['Maven Shade Plugin', '3.5.1', 'Build fat JAR'],
        ['Javalin', '6.1.3', 'HTTP/WebSocket server cho Web UI'],
        ['React', '18.2', 'Frontend Web UI'],
        ['SQLite JDBC', '3.45.1.0', 'Database cục bộ peer & mailbox'],
        ['Gson', '2.10.1', 'JSON serialization/deserialization'],
        ['Docker', '20+', 'Container hóa các thành phần'],
        ['Tailscale', 'Mới nhất', 'VPN cho kết nối P2P qua Internet'],
    ],
    col_widths=[4.5, 3.5, 8.5]
)

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# CHƯƠNG III
# ============================================================
add_heading(doc, 'CHƯƠNG III. PHÂN TÍCH VÀ THIẾT KẾ HỆ THỐNG CHAT P2P', 1)

add_heading(doc, '3.1. Kiến trúc tổng thể', 2)
add_heading(doc, '3.1.1. Mô hình Hybrid P2P với Bootstrap Server', 3)
add_body(doc, 'Hệ thống P2PChat sử dụng mô hình Hybrid P2P — kết hợp ưu điểm của mạng ngang hàng thuần túy (dữ liệu chat truyền trực tiếp P2P, không qua server trung tâm) với một số thành phần tập trung nhẹ để hỗ trợ vận hành. Bootstrap Server đóng vai trò điểm vào mạng duy nhất cho mỗi peer.')
add_body(doc, 'Sau khi có danh sách peer từ Bootstrap, mọi giao tiếp chat (tin nhắn trực tiếp, nhóm, broadcast, chuyển file) đều được truyền trực tiếp giữa các peer qua TCP, không qua Bootstrap Server hay bất kỳ server trung tâm nào. Điều này đảm bảo tính riêng tư, khả năng chịu lỗi khi Bootstrap tạm thời không khả dụng, và giảm tải cho Bootstrap Server.')

add_heading(doc, '3.1.2. Các thành phần và vai trò', 3)
add_table(doc,
    ['Thành phần', 'Vai trò', 'Giao tiếp', 'Lưu trữ'],
    [
        ['Bootstrap Server', 'Điểm vào mạng, đăng ký peer, heartbeat, peer discovery, broadcast sự kiện, dashboard giám sát', 'TCP :9000 (peer)\nHTTP :9001 (dashboard)', 'registry.json'],
        ['Mailbox Server', 'Lưu tin nhắn offline, gửi lại khi người nhận online, theo dõi delivery ack per-member', 'TCP :9100', 'SQLite mailbox.db'],
        ['Peer Node', 'Nhận và gửi tin nhắn P2P, điều phối group chat, truyền file, mã hóa E2EE, retry outbox', '3 TCP port: web, peer, file', 'SQLite tại data/peers/username'],
        ['Peer Web (Frontend)', 'Giao diện người dùng trên trình duyệt, kết nối WebSocket realtime', 'WebSocket → Peer Node', 'Không'],
    ],
    col_widths=[4.0, 6.0, 4.0, 3.0]
)

add_heading(doc, '3.1.3. Luồng xử lý tổng quát', 3)
steps = [
    ('Bước 1 — Peer tham gia mạng: ', 'Peer mới kết nối TCP đến Bootstrap Server (port 9000), gửi REGISTER kèm thông tin. Bootstrap xác nhận bằng REGISTER_ACK, gửi danh sách peer online và thông báo PEER_JOIN đến các peer khác.'),
    ('Bước 2 — Gửi tin nhắn: ', 'Sender tìm endpoint của receiver trong danh sách peer, mở socket TCP trực tiếp đến receiver (port peerPort), gửi DIRECT_MESSAGE đã mã hóa E2EE. Receiver nhận, giải mã, gửi ACK. Tin nhắn được lưu vào SQLite.'),
    ('Bước 3 — Xử lý offline: ', 'Nếu TCP gửi thất bại sau 3 lần retry, sender gửi STORE_MESSAGE đến Mailbox Server. Khi receiver online, nó gửi PULL_MESSAGES, nhận tin nhắn chờ và gửi DELIVERY_ACK.'),
    ('Bước 4 — Chat nhóm: ', 'Sender gửi GROUP_MESSAGE đến tất cả thành viên trực tiếp qua P2P. Coordinator (được chọn bằng HRW) quản lý metadata nhóm. Tin nhắn nhóm offline được gửi qua Mailbox cho thành viên không online.'),
]
for bold_part, normal_part in steps:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(4)
    rb = p.add_run(bold_part)
    rb.bold = True
    rb.font.size = Pt(12)
    rn = p.add_run(normal_part)
    rn.font.size = Pt(12)

# 3.2
add_heading(doc, '3.2. Giao thức trao đổi thông điệp', 2)
add_heading(doc, '3.2.1. Cấu trúc thông điệp', 3)
add_body(doc, 'Tất cả thông điệp trong hệ thống được truyền dưới dạng JSON newline-delimited qua kết nối TCP. Mỗi thông điệp là một dòng JSON kết thúc bằng ký tự xuống dòng \\n. Hệ thống định nghĩa hơn 40 loại thông điệp, chia thành 6 nhóm chức năng.')

add_heading(doc, '3.2.2. Các loại thông điệp', 3)

p_g1 = doc.add_paragraph()
p_g1.paragraph_format.space_before = Pt(4)
r_g1 = p_g1.add_run('Nhóm 1: Bootstrap và Peer Discovery')
r_g1.bold = True
r_g1.font.size = Pt(12)
r_g1.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)

add_table(doc,
    ['Loại thông điệp', 'Hướng', 'Mô tả'],
    [
        ['REGISTER', 'Peer → Bootstrap', 'Gửi thông tin đăng ký: username, host, peerPort, publicKey, keyId'],
        ['REGISTER_ACK', 'Bootstrap → Peer', 'Xác nhận đăng ký thành công, gửi kèm danh sách peer online'],
        ['REGISTER_NACK', 'Bootstrap → Peer', 'Từ chối đăng ký (username trùng hoặc lỗi khác)'],
        ['PEER_JOIN', 'Bootstrap → Peer', 'Thông báo có peer mới tham gia mạng'],
        ['PEER_LEAVE', 'Bootstrap → Peer', 'Thông báo peer rời mạng (graceful hoặc timeout)'],
        ['HEARTBEAT', 'Peer → Bootstrap', 'Gửi keep-alive định kỳ mỗi 5 giây'],
        ['HEARTBEAT_ACK', 'Bootstrap → Peer', 'Xác nhận heartbeat, gửi kèm danh sách peer mới nhất'],
        ['DISCOVER', 'Peer → Bootstrap', 'Yêu cầu danh sách đầy đủ các peer online'],
        ['PEER_LIST', 'Bootstrap → Peer', 'Danh sách tất cả peer online'],
        ['RESOLVE_MAILBOX', 'Peer → Bootstrap', 'Yêu cầu endpoint Mailbox Server'],
        ['RESOLVE_MAILBOX_ACK', 'Bootstrap → Peer', 'Trả về địa chỉ Mailbox Server'],
    ],
    col_widths=[4.5, 4.0, 8.0]
)

p_g2 = doc.add_paragraph()
p_g2.paragraph_format.space_before = Pt(6)
r_g2 = p_g2.add_run('Nhóm 2: Tin nhắn Chat và File Transfer')
r_g2.bold = True
r_g2.font.size = Pt(12)
r_g2.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)

add_table(doc,
    ['Loại thông điệp', 'Hướng', 'Mô tả'],
    [
        ['DIRECT_MESSAGE', 'Peer ↔ Peer', 'Tin nhắn trực tiếp, content đã mã hóa E2EE'],
        ['BROADCAST', 'Peer → tất cả online', 'Tin nhắn gửi đến mọi peer đang online'],
        ['ACK', 'Peer → Peer', 'Xác nhận đã nhận được thông điệp'],
        ['TYPING', 'Peer → Peer', 'Tín hiệu người dùng đang nhập tin'],
        ['FILE_OFFER', 'Peer → Peer/Group', 'Sender gửi offer: filename, kích thước, SHA-256, filePort'],
        ['FILE_ACCEPT', 'Peer → Peer', 'Receiver chấp nhận nhận file'],
        ['FILE_REJECT', 'Peer → Peer', 'Receiver từ chối nhận file'],
        ['FILE_DONE', 'Peer → Peer', 'Sender thông báo đã gửi xong toàn bộ file'],
        ['FILE_ACK', 'Peer → Peer', 'Receiver xác nhận đã nhận đủ dữ liệu và SHA-256 thành công'],
        ['FILE_RESUME', 'Peer → Peer', 'Receiver yêu cầu tiếp tục từ chunk đã checkpoint'],
    ],
    col_widths=[4.5, 4.0, 8.0]
)

p_g3 = doc.add_paragraph()
p_g3.paragraph_format.space_before = Pt(6)
r_g3 = p_g3.add_run('Nhóm 3: Nhóm Chat và Mailbox')
r_g3.bold = True
r_g3.font.size = Pt(12)
r_g3.font.color.rgb = RGBColor(0x1F, 0x4E, 0x79)

add_table(doc,
    ['Loại thông điệp', 'Hướng', 'Mô tả'],
    [
        ['COORD_INIT', 'Peer → Coordinators', 'Khởi tạo metadata nhóm tại các coordinator'],
        ['GROUP_ADD', 'Peer → Coordinator', 'Yêu cầu thêm thành viên vào nhóm'],
        ['GROUP_LEAVE', 'Peer → Coordinator', 'Peer tự rời nhóm'],
        ['GROUP_KICK', 'Coordinator → Peer', 'Thông báo peer bị loại khỏi nhóm'],
        ['GROUP_DISBAND', 'Coordinator → Members', 'Giải tán nhóm'],
        ['GROUP_MESSAGE', 'Sender → Members', 'Tin nhắn nhóm, kèm Lamport timestamp'],
        ['COORD_GOSSIP', 'Coordinator ↔ Coordinator', 'Gossip định kỳ để đồng bộ metadata nhóm'],
        ['STORE_MESSAGE', 'Peer → Mailbox', 'Gửi tin nhắn offline đến Mailbox Server'],
        ['PULL_MESSAGES', 'Peer → Mailbox', 'Yêu cầu lấy tất cả tin nhắn offline'],
        ['DELIVERY_ACK', 'Peer → Mailbox', 'Xác nhận đã nhận được tin nhắn offline'],
    ],
    col_widths=[4.5, 4.0, 8.0]
)

# 3.3
add_heading(doc, '3.3. Cơ chế Peer Discovery', 2)
add_body(doc, 'PeerRegistry bên trong Bootstrap Server sử dụng ConcurrentHashMap để lưu trữ thông tin của mỗi peer: username → PeerInfo(host, peerPort, publicKey, keyId, lastHeartbeat, online). PeerRegistry được lưu bền vững ra file registry.json sau mỗi thao tác thay đổi, và được đọc lại khi Bootstrap Server khởi động lại. Nhờ đó, trạng thái mạng không bị mất khi Bootstrap Server restart.')

add_body(doc, 'Khi peer gửi REGISTER, Bootstrap Server xử lý theo các bước: (1) kiểm tra username trùng lặp; (2) phát hiện NAT — so sánh IP quảng bá với IP thực từ socket; (3) lưu thông tin peer vào registry; (4) gửi offline messages nếu có; (5) broadcast PEER_JOIN đến tất cả peer online; (6) trả REGISTER_ACK kèm danh sách peer online.')

add_heading(doc, '3.3.2. Lan truyền cấu trúc mạng kiểu đẩy', 3)
add_body(doc, 'Sau khi peer mới đăng ký thành công, Bootstrap Server đẩy thông tin về peer mới đến tất cả peer đang online qua PEER_JOIN. Khi một peer rời mạng, Bootstrap đẩy PEER_LEAVE. Cơ chế đẩy này đảm bảo mỗi peer online luôn có danh sách peer mới nhất. Ngoài ra, HEARTBEAT_ACK cũng gửi kèm danh sách peer mới nhất sau mỗi 5 giây.')

add_heading(doc, '3.3.3. Bộ đệm định tuyến và Khả năng tự phục hồi', 3)
add_body(doc, 'Mỗi peer lưu trong bảng known_peers: username, host, port, trạng thái online, last_seen — đóng vai trò bộ đệm định tuyến cục bộ. Khi Bootstrap Server không khả dụng, peer sử dụng RecentPeersCache (lưu 5 peer gần nhất) để duy trì kết nối trực tiếp. Khi Bootstrap khôi phục, peer tự đăng ký lại qua REGISTER.')

# 3.4
add_heading(doc, '3.4. Cơ chế chat nhóm DHT-Lite', 2)
add_heading(doc, '3.4.1. Bầu cử phi tập trung bằng Rendezvous Hashing', 3)
add_body(doc, 'Mỗi nhóm chat có K=3 coordinator được chọn bằng thuật toán HRW (Highest Random Weight). HRW đảm bảo với cùng một tập thành viên và cùng một groupId, tất cả peer đều tính ra cùng một tập hợp K coordinator mà không cần trao đổi thêm. Công thức: hash_i = SHA256(groupId + peerId); K peer có hash_i lớn nhất làm coordinator.')

add_heading(doc, '3.4.2. Giao thức Gossip và Giải quyết xung đột', 3)
add_body(doc, 'Các coordinator đồng bộ metadata nhóm qua giao thức Gossip định kỳ mỗi 5 giây. Mỗi coordinator gửi COORD_GOSSIP đến tất cả coordinator khác chứa vector version: {version, members, updatedAt}. Nếu nhận được version mới hơn thì cập nhật local group_cache. Nếu không nhận được gossip trong 3 chu kỳ liên tiếp (15 giây), coordinator đó bị coi là dead. Xung đột được giải quyết bằng timestamp updatedAt — metadata mới hơn được ưu tiên.')

add_heading(doc, '3.4.3. Nhất quán cuối trong Control Plane', 3)
add_body(doc, 'Hệ thống chat nhóm đạt eventual consistency trong control plane nhờ: Gossip định kỳ mỗi 5 giây; Monotonic clock (LamportClock) đảm bảo thứ tự nhất quán; Buffer 200ms khi nhận tin nhắn nhóm để xử lý tin đến không đúng thứ tự; LazyRepairManager phát hiện CACHE_STALE và gửi GROUP_RESYNC_REQ khi peer online trở lại.')

# 3.5
add_heading(doc, '3.5. Cơ chế truyền file', 2)
add_body(doc, 'Trước khi truyền dữ liệu, sender và receiver thỏa thuận qua 3 bước: Bước 1 — Sender gửi FILE_OFFER chứa transferId, fileName, fileSize, sha256, filePort; Bước 2 — Receiver kiểm tra kích thước và phản hồi FILE_ACCEPT hoặc FILE_REJECT; Bước 3 — Nếu chấp nhận, receiver mở socket TCP đến filePort của sender và bắt đầu nhận dữ liệu theo chunk 64 KB.')

add_table(doc,
    ['Thông số', 'Giá trị'],
    [
        ['Kích thước chunk', '64 KB (65,536 bytes)'],
        ['Giới hạn file', '100 MB'],
        ['Xác minh toàn vẹn', 'SHA-256 toàn bộ file'],
        ['Resume sau gián đoạn', 'Có (checkpoint chunk cuối)'],
        ['File group offer', 'Có (gửi đồng thời nhiều receiver)'],
    ],
    col_widths=[6.0, 10.5]
)

# 3.6
doc.add_paragraph()
add_heading(doc, '3.6. Cơ chế Store-and-Forward', 2)
add_body(doc, 'Mailbox Server là một TCP server độc lập (port 9100), sử dụng SQLite làm cơ sở dữ liệu. Bảng offline_messages lưu các tin nhắn với: id (messageId), receiver, encrypted_payload (blob E2EE), sender, timestamp, ttl (Unix timestamp hết hạn 7 ngày), status (STORED/DELIVERED/EXPIRED), group_id. Bảng group_delivery_acks theo dõi delivery status per-member cho group messages.')

add_body(doc, 'Mailbox Server có scheduled task chạy định kỳ để đánh dấu tin nhắn hết hạn (status = EXPIRED) và thông báo MAILBOX_MESSAGE_EXPIRED cho receiver nếu online. Circuit breaker được triển khai phía peer: khi Mailbox liên tục không phản hồi (3 lần), tạm dừng gửi qua Mailbox trong 30 giây để tránh tắc nghẽn.')

# 3.7
add_heading(doc, '3.7. Thiết kế cơ sở dữ liệu', 2)
add_heading(doc, '3.7.1. Peer SQLite Schema', 3)
add_table(doc,
    ['Bảng', 'Mô tả'],
    [
        ['messages', 'Lưu tất cả tin nhắn: direct, group, broadcast, system, file offer'],
        ['known_peers', 'Peer đã biết: username, host, port, online flag, last_seen'],
        ['group_cache', 'Metadata nhóm DHT-lite: group_id, name, coordinator, members (JSON), timestamps'],
        ['recent_peers', 'Bootstrap fallback cache: 5 peer gần nhất đã kết nối'],
        ['outbound_messages', 'Durable outbox: id, receiver, type, content, status, retry_count'],
        ['file_transfers', 'Metadata file transfer: transfer_id, file_name, file_size, sha256, status'],
        ['file_chunks', 'Checkpoint chunk: transfer_id, chunk_number, received (boolean)'],
    ],
    col_widths=[4.5, 12.0]
)

add_heading(doc, '3.7.2. Mailbox SQLite Schema', 3)
add_table(doc,
    ['Bảng', 'Mô tả'],
    [
        ['offline_messages', 'Tin nhắn offline: id, receiver, encrypted_payload, sender, timestamp, ttl, status, group_id'],
        ['group_delivery_acks', 'Delivery ack per-member cho group message: message_id + username, delivered_at'],
    ],
    col_widths=[4.5, 12.0]
)

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# CHƯƠNG IV
# ============================================================
add_heading(doc, 'CHƯƠNG IV. TRIỂN KHAI HỆ THỐNG', 1)

add_heading(doc, '4.1. Môi trường và công nghệ sử dụng', 2)
add_table(doc,
    ['Công nghệ', 'Phiên bản', 'Vai trò'],
    [
        ['Java', '17+', 'Backend: Bootstrap, Mailbox, Peer Node'],
        ['Maven (Shade Plugin)', '3.9+', 'Build fat JAR cho mỗi module'],
        ['Javalin', '6.1.3', 'HTTP server + WebSocket trong Peer Node'],
        ['React', '18.2', 'Frontend Web UI'],
        ['SQLite JDBC', '3.45.1.0', 'Database cục bộ tại mỗi peer và Mailbox'],
        ['Gson', '2.10.1', 'JSON serialization/deserialization'],
        ['Docker', '20+', 'Container hóa toàn bộ hệ thống'],
        ['Tailscale', 'Mới nhất', 'VPN cho kết nối P2P qua Internet'],
        ['Python', '3.x', 'Peer launcher HTTP server (port 9200)'],
    ],
    col_widths=[4.5, 3.0, 9.0]
)

add_heading(doc, '4.2. Cấu trúc mã nguồn', 2)
add_body(doc, 'Dự án được tổ chức thành 4 module độc lập: bootstrap-server, mailbox-server, peer-node và peer-web (React frontend). Script run.sh điều phối toàn bộ quá trình build, start, stop và quản lý peer. Script build.sh thực hiện build React và 3 Maven modules. Dockerfile triển khai multi-stage build cho peer-node.')

add_table(doc,
    ['Module', 'Thư mục/File chính', 'Chức năng'],
    [
        ['bootstrap-server', 'BootstrapApp.java, BootstrapServer.java, PeerRegistry.java, ClientHandler.java', 'Entry point, TCP server, registry peer, dashboard'],
        ['mailbox-server', 'MailboxApp.java, MailboxServer.java, MailboxRepository.java', 'TCP server lưu tin nhắn offline'],
        ['peer-node', 'PeerApp.java, PeerNode.java, PeerServer.java, CoordinatorManager.java, FileTransferManager.java', 'Peer TCP server/client, group coordinator, file transfer, E2EE'],
        ['peer-web', 'App.jsx, api.js, components/', 'React Web UI, WebSocket realtime'],
    ],
    col_widths=[4.0, 7.0, 5.5]
)

# 4.3
add_heading(doc, '4.3. Triển khai Bootstrap Server', 2)
add_heading(doc, '4.3.1. Khởi tạo và vòng lặp kết nối', 3)
add_body(doc, 'BootstrapApp là entry point, nhận các tham số dòng lệnh: --port (mặc định 9000), --dashboard-port (mặc định 9001), --mailbox (host và port của Mailbox Server). BootstrapServer tạo ServerSocket lắng nghe trên port đã chỉ định. Vòng lặp chính dùng serverSocket.accept() để chờ kết nối từ peer. Mỗi kết nối được giao cho một ClientHandler chạy trong thread riêng từ CachedThreadPool.')

add_code_block(doc, '# Build Bootstrap Server\nmvn clean package -DskipTests -f bootstrap-server/pom.xml\n\n# Khởi chạy Bootstrap Server\njava -jar bootstrap-server/target/bootstrap-server.jar \\\n  --port 9000 \\\n  --dashboard-port 9001 \\\n  --mailbox localhost 9100')

add_heading(doc, '4.3.2. Quản lý Peer Registry và Dead Peer Detection', 3)
add_body(doc, 'PeerRegistry sử dụng ConcurrentHashMap để lưu trữ thông tin peer (thread-safe). ScheduledExecutorService chạy dead-peer detection mỗi 5 giây: nếu now - lastHeartbeat > 45000ms, peer bị xóa và broadcastPeerLeave được gọi. PeerRegistry tự động lưu ra registry.json sau mỗi thao tác thay đổi và đọc lại khi Bootstrap restart — đảm bảo trạng thái mạng bảo toàn qua các lần restart.')

add_heading(doc, '4.3.3. Triển khai bằng Docker', 3)
add_code_block(doc, '# Build Docker image\ndocker build -t p2pchat-bootstrap -f bootstrap-server/Dockerfile bootstrap-server/\n\n# Chạy Bootstrap container\ndocker run -d --name bootstrap \\\n  -p 9000:9000 -p 9001:9001 \\\n  p2pchat-bootstrap \\\n  --port 9000 --dashboard-port 9001 --mailbox mailbox:9100')

# 4.4
add_heading(doc, '4.4. Triển khai Peer Node', 2)
add_heading(doc, '4.4.1. Khởi tạo và đăng ký với Bootstrap', 3)
add_body(doc, 'Khi PeerNode.start() được gọi, thứ tự khởi tạo: (1) Mở PeerServer (ServerSocket) trên peerPort; (2) Khởi tạo E2EECrypto — tạo hoặc tải cặp khóa RSA từ file; (3) Khởi tạo DatabaseManager kết nối SQLite; (4) Khởi tạo PeerClient, MailboxClient, CoordinatorManager, LazyRepairManager, FileTransferManager; (5) Khởi tạo WebServer (Javalin); (6) Gửi RESOLVE_MAILBOX đến Bootstrap; (7) Gửi REGISTER kèm thông tin; (8) Khởi động các scheduler nền; (9) Gọi LazyRepairManager để đồng bộ group state sau khi restart.')

add_code_block(doc, '# Khởi chạy Peer Node\njava -jar peer-node/target/peer-node.jar \\\n  --username alice \\\n  --host localhost \\\n  --port 34143 \\\n  --bootstrap localhost:9000 \\\n  --mailbox localhost:9100 \\\n  --web 33143')

add_heading(doc, '4.4.2. Heartbeat và tự phục hồi kết nối', 3)
add_body(doc, 'Heartbeat được gửi qua ScheduledExecutorService mỗi 5 giây. Mỗi lần gửi heartbeat, peer mở socket TCP đến Bootstrap Server, gửi HEARTBEAT kèm username và đóng socket ngay sau khi nhận HEARTBEAT_ACK. Nếu heartbeat thất bại liên tiếp, peer vẫn tiếp tục hoạt động bình thường — nó vẫn có thể nhận và gửi tin nhắn trực tiếp với các peer khác. Khi kết nối Bootstrap được khôi phục, peer tự đăng ký lại qua REGISTER.')

# 4.5
add_heading(doc, '4.5. Triển khai Chat trực tiếp và E2EE', 2)
add_body(doc, 'Khi người dùng gửi tin nhắn qua Web UI, PeerNode nhận request qua REST API /api/msg: (1) tạo UUID cho messageId, gắn timestamp; (2) lưu vào outbound_messages với status = PENDING; (3) tìm endpoint receiver trong known_peers; (4) mở socket TCP đến receiver:peerPort, gửi DIRECT_MESSAGE JSON; (5) nếu nhận ACK trong 6 giây → DELIVERED_DIRECT; (6) nếu thất bại sau 2 retry → gửi qua MailboxClient → STORED_MAILBOX.')
add_body(doc, 'E2EE: mỗi peer tạo cặp khóa RSA 2048-bit khi khởi động lần đầu. Khi gửi tin, E2EECrypto.encrypt(plaintext, receiverPublicKey) mã hóa nội dung. Khi nhận, E2EECrypto.decrypt(ciphertext, myPrivateKey) giải mã. Mailbox Server và Bootstrap Server không thể đọc nội dung tin nhắn.')

# 4.6
add_heading(doc, '4.6. Triển khai Chat nhóm', 2)
add_body(doc, 'Khi người dùng tạo nhóm qua Web UI (/api/group/create): (1) tạo UUID cho groupId; (2) tính K=3 coordinator bằng HRWHash.topK(members, groupId, K=3); (3) lưu GroupInfo vào group_cache; (4) gửi COORD_INIT đến tất cả K coordinator; (5) gửi GROUP_JOINED đến mỗi thành viên.')
add_body(doc, 'CoordinatorManager chạy gossip scheduler: mỗi 5 giây gửi COORD_GOSSIP đến tất cả coordinator khác. Khi gửi tin nhắn nhóm, sender tăng LamportClock, gắn timestamp vào message, rồi multicast GROUP_MESSAGE đến từng thành viên. Peer nhận đưa vào Lamport Buffer 200ms, sắp xếp theo timestamp và hiển thị.')

# 4.7
add_heading(doc, '4.7. Triển khai Truyền file', 2)
add_body(doc, 'Khi người dùng chọn file trong Web UI: backend kiểm tra kích thước (giới hạn 100 MB), tính SHA-256 hash, khởi tạo FileTransferManager, mở FileTransferServer (ServerSocket) trên filePort, gửi FILE_OFFER đến receiver. Khi receiver chấp nhận, FileTransferClient mở socket TCP đến filePort của sender. Sender đọc file theo chunk 64 KB, gửi mỗi chunk với 4 bytes độ dài + dữ liệu. Sau EOF, sender gửi FILE_DONE. Receiver kiểm tra SHA-256 và gửi FILE_ACK.')
add_body(doc, 'Progress được cập nhật realtime qua WebSocket: (bytesReceived / fileSize) * 100%. Checkpoint được lưu sau mỗi chunk vào file_chunks table — hỗ trợ resume sau gián đoạn kết nối.')

# 4.8
add_heading(doc, '4.8. Triển khai Mailbox Server và Outbox Engine', 2)
add_body(doc, 'Outbox Manager sử dụng state machine cho mỗi outbound message: PENDING → (retry < MAX) → PENDING (retryCount++); → (retry >= MAX) → STORED_MAILBOX → (receiver DELIVERY_ACK) → DELIVERED_MAILBOX. Exponential backoff: Retry 1-3: 5 giây; Retry 4-6: 30 giây; Retry 7-9: 2 phút; Retry 10-12: 10 phút. Sau 12 retry, message được chuyển sang mailbox.')
add_body(doc, 'Circuit breaker: khi Mailbox Server không phản hồi 3 lần liên tiếp, STORE_MESSAGE bị tạm dừng trong 30 giây. Sau đó một lần half-open được thực hiện — nếu thành công thì circuit đóng, nếu thất bại thì circuit mở tiếp 30 giây.')

# 4.9
add_heading(doc, '4.9. Giao diện Web và đóng gói Docker', 2)
add_heading(doc, '4.9.1. Kiến trúc React và WebSocket real-time', 3)
add_table(doc,
    ['Component', 'Vai trò'],
    [
        ['App.jsx', 'Component root, quản lý routing, WebSocket connection'],
        ['Sidebar.jsx', 'Danh sách peer online/offline, danh sách nhóm'],
        ['ChatArea.jsx', 'Khu vực hiển thị tin nhắn'],
        ['MessageInput.jsx', 'Ô nhập tin nhắn, gửi typing signal'],
        ['FileTransfers.jsx', 'Quản lý file transfer, progress bar'],
        ['OutboxView.jsx', 'Trạng thái outbox (PENDING, DELIVERED, STORED_MAILBOX)'],
    ],
    col_widths=[4.5, 12.0]
)

add_heading(doc, '4.9.2. Đóng gói Docker — Multi-stage Build', 3)
add_code_block(doc, '# Multi-stage Dockerfile (peer-node):\n# Stage 1 (build-web): Node 20 Alpine, build React\n# Stage 2 (build-java): Maven + Eclipse Temurin 17, build fat JAR\n# Stage 3 (runtime): Eclipse Temurin 17 JRE Alpine, run JAR\n\n# Build và chạy toàn bộ stack\n./run.sh build\n./run.sh start')

add_heading(doc, '4.9.3. Các peer mặc định khi dùng run.sh', 3)
add_table(doc,
    ['Peer', 'Web UI', 'Peer TCP Port', 'File TCP Port'],
    [
        ['alice', 'http://localhost:33143', '34143', '35143'],
        ['bob', 'http://localhost:33144', '34144', '35144'],
        ['duc', 'http://localhost:12345', '13345', '14345'],
        ['hoang', 'http://localhost:12346', '13346', '14346'],
        ['hoan', 'http://localhost:12349', '13349', '14349'],
    ],
    col_widths=[3.5, 5.5, 4.0, 4.0]
)

add_heading(doc, '4.9.4. Hạ tầng hỗ trợ', 3)
add_table(doc,
    ['Service', 'URL/Port'],
    [
        ['Bootstrap TCP', 'localhost:9000'],
        ['Bootstrap Dashboard', 'http://localhost:9001'],
        ['Mailbox TCP', 'localhost:9100'],
        ['Peer Launcher UI', 'http://localhost:9200'],
    ],
    col_widths=[6.0, 10.5]
)

add_heading(doc, '4.10. Triển khai trên Internet qua Tailscale', 2)
add_body(doc, 'Khi các máy tính ở mạng nội bộ (LAN) khác nhau, chúng không thể giao tiếp trực tiếp qua TCP vì NAT. Giải pháp: Tailscale — mạng riêng ảo (VPN) tạo kết nối P2P giữa các máy. Quy trình thiết lập: (1) Tất cả người dùng cài Tailscale, đăng nhập cùng một Tailnet; (2) Một người làm Quản trị mạng, mời những người khác; (3) Mỗi người chạy "tailscale ip -4" để lấy IP Tailscale (dải 100.x.x.x); (4) Khởi động Bootstrap + Mailbox + Peer với IP Tailscale tương ứng.')

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# CHƯƠNG V
# ============================================================
add_heading(doc, 'CHƯƠNG V. THỬ NGHIỆM HỆ THỐNG VÀ ĐÁNH GIÁ', 1)

add_heading(doc, '5.1. Môi trường thử nghiệm', 2)
add_heading(doc, '5.1.1. Phần cứng và phần mềm', 3)
add_table(doc,
    ['Thành phần', 'Cấu hình/Phiên bản'],
    [
        ['Hệ điều hành', 'Windows 10/11, Linux (WSL2)'],
        ['Java JDK', '17'],
        ['Docker', '20+'],
        ['Tailscale', 'Phiên bản mới nhất'],
        ['Trình duyệt', 'Chrome / Edge / Firefox'],
    ],
    col_widths=[5.5, 11.0]
)

add_heading(doc, '5.1.2. Cấu hình mạng thử nghiệm', 3)
add_body(doc, 'Thử nghiệm được thực hiện trên hai môi trường: (1) Local LAN — các peer chạy trên cùng mạng LAN, kết nối qua Docker bridge network p2p-net, DNS resolution giữa các container qua tên container (vd: bootstrap:9000, mailbox:9100); (2) Tailscale VPN — các peer ở mạng LAN khác nhau kết nối qua Tailscale VPN, IP Tailscale dải 100.x.x.x cho phép kết nối TCP trực tiếp qua Internet.')

# 5.2
add_heading(doc, '5.2. Các kịch bản thử nghiệm', 2)
add_heading(doc, '5.2.1. Kịch bản 1: Đăng ký và Peer Discovery', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'Khởi động Bootstrap Server', 'Bootstrap chạy, dashboard tại http://localhost:9001'],
        ['2', 'Khởi động peer alice', 'alice đăng ký thành công, hiển thị trong dashboard'],
        ['3', 'Khởi động peer bob', 'bob đăng ký, alice nhận PEER_JOIN trong Web UI'],
        ['4', 'Alice gọi /discover', 'Nhận danh sách bob trong peer list'],
        ['5', 'Dashboard refresh', 'Hiển thị 2 peer online với thông tin đầy đủ'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

add_heading(doc, '5.2.2. Kịch bản 2: Nhắn tin trực tiếp (Direct Message)', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'Alice gửi tin nhắn cho bob (bob đang online)', 'Tin nhắn gửi qua TCP trực tiếp, ACK nhận được'],
        ['2', 'Bob nhận tin', 'Tin hiển thị realtime trong chat window của bob'],
        ['3', 'Bob đóng ứng dụng', 'Bob offline trong dashboard sau 45 giây'],
        ['4', 'Alice gửi tin cho bob (bob offline)', 'TCP thất bại sau 3 retry'],
        ['5', 'Alice gửi STORE_MESSAGE đến Mailbox', 'STORE_ACK, outbox = STORED_MAILBOX'],
        ['6', 'Bob khởi động lại', 'Bob online, PULL_MESSAGES → nhận tin từ Mailbox'],
        ['7', 'Bob gửi DELIVERY_ACK', 'Mailbox đánh dấu DELIVERED'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

add_heading(doc, '5.2.3. Kịch bản 3: Chat nhóm (Group Chat)', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'Alice tạo group với bob, duc', 'Group được tạo, coordinator = alice (HRW), COORD_INIT gửi'],
        ['2', 'Alice gửi tin nhắn nhóm', 'Tin nhắn gửi đến tất cả member, hiển thị đúng thứ tự'],
        ['3', 'Duc offline, bob online', 'Bob nhận tin; duc nhận khi online trở lại qua Mailbox'],
        ['4', 'Alice kick duc khỏi group', 'Duc nhận GROUP_KICKED, không nhận tin nhóm tiếp theo'],
        ['5', 'Alice gửi GROUP_DISBAND', 'Group giải tán, tất cả member nhận GROUP_DISBANDED'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

add_heading(doc, '5.2.4. Kịch bản 4: Broadcast', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'Alice gửi broadcast "Chào mọi người"', 'Tin gửi đến tất cả peer đang online'],
        ['2', 'Peer offline không nhận', 'Tin broadcast không lưu offline (chỉ online)'],
        ['3', 'Xem lịch sử broadcast', 'Lịch sử hiển thị đầy đủ qua /api/broadcast-history'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

add_heading(doc, '5.2.5. Kịch bản 5: Chuyển file P2P', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'Alice chọn file (< 100 MB), nhấn gửi đến bob', 'FILE_OFFER gửi, bob thấy thông báo nhận file'],
        ['2', 'Bob chấp nhận (FILE_ACCEPT)', 'Transfer bắt đầu, progress bar hiển thị'],
        ['3', 'Xem progress realtime', 'Progress bar cập nhật qua WebSocket'],
        ['4', 'Hoàn tất transfer', 'SHA-256 xác minh thành công, FILE_ACK gửi'],
        ['5', 'Gửi file > 100 MB', 'Hệ thống báo lỗi vượt giới hạn'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

add_heading(doc, '5.2.6. Kịch bản 6: Peer Churn (crash/leave)', 3)
add_table(doc,
    ['Bước', 'Thao tác', 'Kết quả mong đợi'],
    [
        ['1', 'peer04 graceful shutdown (PEER_LEAVE)', 'Bootstrap broadcast PEER_LEAVE ngay lập tức'],
        ['2', 'peer02 crash (force kill)', 'Không gửi PEER_LEAVE'],
        ['3', 'Chờ 45 giây', 'Bootstrap phát hiện peer02 dead, broadcast PEER_LEAVE'],
        ['4', 'Kiểm tra dashboard', 'peer02, peer04 hiển thị offline chính xác'],
        ['5', 'peer02 khởi động lại', 'peer02 đăng ký lại, PEER_JOIN broadcast đến tất cả'],
    ],
    col_widths=[1.5, 6.5, 8.5]
)

# 5.3
add_heading(doc, '5.3. Kết quả thử nghiệm', 2)
add_heading(doc, '5.3.1. Peer Discovery', 3)
add_table(doc,
    ['Tiêu chí đánh giá', 'Kết quả thực tế'],
    [
        ['Thời gian đăng ký peer', '< 500 ms'],
        ['Thời gian peer join broadcast đến peers khác', '< 1 giây'],
        ['Số lượng peer thử nghiệm tối đa', '5 peer đồng thời'],
        ['NAT detection với Docker bridge IP', 'Hoạt động đúng, IP được ghi đè'],
        ['Bootstrap restart recovery', 'Peer tự đăng ký lại thành công'],
    ],
    col_widths=[8.5, 8.0]
)

add_heading(doc, '5.3.2. Direct Message và Offline Message', 3)
add_table(doc,
    ['Tiêu chí đánh giá', 'Kết quả thực tế'],
    [
        ['Độ trễ tin nhắn (cùng LAN)', '< 100 ms'],
        ['ACK timeout', '6 giây'],
        ['Retry tối đa trước khi gửi mailbox', '3 lần'],
        ['Outbox persistence khi peer restart', 'Tin không bị mất'],
        ['Tin nhắn offline lưu và giao khi online', 'Hoạt động đúng'],
        ['TTL 7 ngày cho tin offline', 'Hoạt động đúng'],
        ['Group message delivery ack per-member', 'Hoạt động đúng'],
    ],
    col_widths=[8.5, 8.0]
)

add_heading(doc, '5.3.3. Group Chat và File Transfer', 3)
add_table(doc,
    ['Tiêu chí đánh giá', 'Kết quả thực tế'],
    [
        ['Tạo group và bầu coordinator HRW', 'Thành công'],
        ['Thêm/kick/rời nhóm', 'Hoạt động đúng'],
        ['Offline member nhận tin nhóm qua mailbox', 'Hoạt động đúng'],
        ['Group resync khi online lại', 'Hoạt động đúng'],
        ['File transfer chunk 64 KB', 'Thành công'],
        ['SHA-256 verification', 'Hoạt động đúng'],
        ['Progress bar realtime', 'Cập nhật qua WebSocket'],
        ['File group offer đến nhiều receiver', 'Hoạt động đúng'],
    ],
    col_widths=[8.5, 8.0]
)

add_heading(doc, '5.3.4. Churn Test', 3)
add_body(doc, 'Kịch bản mô phỏng churn test được thực hiện bằng script PowerShell: ./churn-test.ps1 -PeerCount 6 -TotalRounds 12. Kết quả: Bootstrap phát hiện dead peer sau đúng 45 giây (9 chu kỳ heartbeat), broadcast PEER_LEAVE đến tất cả peer online, dashboard cập nhật trạng thái chính xác. Peer restart và đăng ký lại thành công, PEER_JOIN được broadcast đến các peer còn lại.')

# 5.4
add_heading(doc, '5.4. Đánh giá tổng thể', 2)
add_heading(doc, '5.4.1. Ưu điểm', 3)
advantages = [
    'Kiến trúc phân tán rõ ràng: Bootstrap chỉ điều phối metadata, dữ liệu chat truyền P2P trực tiếp, đảm bảo tính riêng tư.',
    'Độ tin cậy cao: Durable outbox + ACK + retry + exponential backoff đảm bảo tin nhắn được giao đến người nhận.',
    'Store-and-forward hiệu quả: Tin offline được lưu tự động và giao khi người nhận trở lại online.',
    'Bảo mật: E2EE cho payload tin nhắn; Mailbox Server không thể đọc nội dung.',
    'Hỗ trợ nhiều tính năng: Direct message, group chat, broadcast, file transfer — đầy đủ cho nhu cầu thực tế.',
    'DHT-lite nhóm: HRW coordinator phân bố đều, gossip đồng bộ metadata, eventual consistency.',
    'Docker hóa: Dễ triển khai, quản lý nhiều peer bằng script run.sh.',
    'Dashboard giám sát: Theo dõi trạng thái Bootstrap và peer realtime.',
    'Giao diện Web: React UI với WebSocket realtime, UX thân thiện.',
    'Kết nối Internet: Hỗ trợ Tailscale VPN cho kết nối xuyên mạng nội bộ.',
]
for adv in advantages:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(adv)
    run.font.size = Pt(12)

add_heading(doc, '5.4.2. Hạn chế', 3)
limitations = [
    'Điểm thất bại một phần: Bootstrap Server và Mailbox Server là thành phần tập trung; nếu một trong hai chết, peer không thể đăng ký mới hoặc gửi tin offline (peer đã đăng ký vẫn chat P2P trực tiếp được).',
    'E2EE chưa hoàn toàn: Chỉ mã hóa payload, chưa có Diffie-Hellman handshake, chưa có perfect forward secrecy.',
    'Không có xác thực: Không có cơ chế đăng nhập/mật khẩu; username có thể bị mạo danh.',
    'File transfer giới hạn 100 MB: Không hỗ trợ file lớn hơn.',
    'Group resync chưa tối ưu: Khi nhóm lớn, gossip có thể tạo nhiều message trùng lặp.',
    'Không có load balancing: Khi số peer tăng lên, Bootstrap trở thành nút thắt cổ chai.',
]
for lim in limitations:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(lim)
    run.font.size = Pt(12)

add_heading(doc, '5.4.3. So sánh với hệ thống tương tự', 3)
add_table(doc,
    ['Tiêu chí', 'P2PChat (nhóm)', 'Signal', 'Discord', 'Telegram'],
    [
        ['Mô hình', 'Hybrid P2P', 'Hybrid P2P', 'Client-Server', 'Client-Server'],
        ['E2EE', 'Có (payload)', 'Toàn diện', 'Tùy chọn', 'Tùy chọn'],
        ['Tin offline', 'Có (Mailbox)', 'Có', 'Có', 'Có'],
        ['Group chat', 'Có (DHT-lite)', 'Có', 'Có', 'Có'],
        ['File transfer P2P', 'Có (trực tiếp)', 'Có', 'Có', 'Có'],
        ['Giao diện', 'Web (React)', 'Mobile/Desktop', 'Web/App', 'Web/App'],
        ['Chạy local', 'Có (Docker)', 'Không', 'Không', 'Không'],
        ['Mã nguồn mở', 'Có (hoàn toàn)', 'Một phần', 'Không', 'Không'],
    ],
    col_widths=[4.0, 4.0, 3.0, 3.0, 3.0]
)

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# CHƯƠNG VI
# ============================================================
add_heading(doc, 'CHƯƠNG VI. KẾT LUẬN', 1)

add_heading(doc, '6.1. Tổng kết kết quả đạt được', 2)
add_body(doc, 'Trong quá trình thực hiện đề tài, nhóm đã xây dựng thành công một hệ thống chat ngang hàng (P2P Chat) hoàn chỉnh với đầy đủ các thành phần và tính năng theo mục tiêu đề ra:')

results = [
    ('Bootstrap Server: ', 'Quản lý đăng ký peer, heartbeat, peer discovery, broadcast sự kiện, dashboard giám sát, và điều phối mailbox endpoint. Hỗ trợ NAT detection cho môi trường Docker và Tailscale.'),
    ('Mailbox Server: ', 'Lưu trữ tin nhắn offline bằng SQLite, hỗ trợ TTL 7 ngày và delivery ack per-member cho group message. Circuit breaker đảm bảo không tắc nghẽn khi Mailbox không khả dụng.'),
    ('Peer Node: ', 'Mỗi người dùng là một peer độc lập với TCP server P2P, Web API Javalin + WebSocket realtime, durable outbox, E2EE payload, coordinator DHT-lite, và file transfer trực tiếp.'),
    ('Peer Web (Frontend): ', 'Giao diện React đầy đủ tính năng: chat trực tiếp, quản lý nhóm, broadcast, chuyển file với progress bar, trạng thái outbox realtime.'),
    ('Docker deployment: ', 'Toàn bộ hệ thống container hóa với script quản lý run.sh, hỗ trợ nhiều peer đồng thời và kết nối qua Internet bằng Tailscale VPN.'),
]
for bold_part, normal_part in results:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(4)
    rb = p.add_run(bold_part)
    rb.bold = True
    rb.font.size = Pt(12)
    rn = p.add_run(normal_part)
    rn.font.size = Pt(12)

add_heading(doc, '6.2. Các vấn đề đã giải quyết', 2)
add_table(doc,
    ['Vấn đề đặt ra', 'Giải pháp triển khai'],
    [
        ['Peer mới cần biết peer khác để tham gia mạng', 'Bootstrap Server làm điểm vào duy nhất'],
        ['NAT/Firewall chặn kết nối P2P qua Internet', 'Tailscale VPN + NAT detection tại Bootstrap'],
        ['Người nhận offline không nhận được tin', 'Store-and-forward qua Mailbox Server, TTL 7 ngày'],
        ['Tin nhắn có thể bị mất khi peer crash', 'Durable outbox với retry và exponential backoff'],
        ['Gửi tin thất bại (receiver offline dài)', 'Tự động chuyển sang mailbox sau ngưỡng retry'],
        ['Peer ngắt kết nối đột ngột không báo', 'Heartbeat + dead-peer detection (45s timeout)'],
        ['Mailbox/trung gian có thể đọc tin nhắn', 'E2EE payload bằng public key người nhận'],
        ['Khó quản lý nhiều peer đồng thời', 'Docker + run.sh + peer launcher UI'],
        ['Quản lý nhóm chat phân tán', 'DHT-Lite với HRW coordinator và gossip protocol'],
        ['Đồng bộ nhóm khi peer offline lâu', 'LazyRepairManager + GROUP_RESYNC_REQ/RESP'],
    ],
    col_widths=[8.0, 8.5]
)

add_heading(doc, '6.3. Hướng phát triển tiếp theo', 2)
future_works = [
    'Xác thực người dùng: Thêm cơ chế đăng nhập, chữ ký số để chống mạo danh username và tăng cường bảo mật.',
    'E2EE toàn diện: Triển khai Double Ratchet hoặc Signal Protocol để đạt perfect forward secrecy.',
    'Structured P2P (DHT): Thay thế Bootstrap Server bằng Kademlia/Chord để loại bỏ hoàn toàn điểm thất bại tập trung.',
    'Hỗ trợ file lớn: Nâng giới hạn lên > 100 MB bằng cách chia chunk nhỏ hơn và hỗ trợ transfer song song.',
    'Voice/Video call: Mở rộng từ TCP → UDP/RTP cho truyền voice/video P2P real-time.',
    'Đa Bootstrap Server: Triển khai nhiều bootstrap instance để tăng độ sẵn sàng và loại bỏ SPOF.',
    'Tối ưu group resync: Cải thiện giao thức gossip để giảm duplicate message khi nhóm lớn.',
    'Push notification: Tích hợp thông báo đẩy khi có tin nhắn mới cho trải nghiệm mobile.',
]
for fw in future_works:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(3)
    run = p.add_run(fw)
    run.font.size = Pt(12)

add_heading(doc, '6.4. Bài học kinh nghiệm', 2)
lessons = [
    ('Thiết kế giao thức trước, triển khai sau: ', 'Định nghĩa message type và luồng tương tác rõ ràng trước khi code giúp triển khai nhanh hơn và ít lỗi hơn rất nhiều.'),
    ('Outbox pattern là chìa khóa độ tin cậy: ', 'Durable outbox là thành phần quan trọng nhất để đạt được độ tin cậy cao trong hệ thống phân tán — không có nó, tin nhắn dễ bị mất khi peer crash.'),
    ('NAT detection phức tạp hơn dự kiến: ', 'Cần xử lý nhiều trường hợp khác nhau: Docker bridge IP, Tailscale VPN, router NAT — mỗi môi trường có đặc thù riêng.'),
    ('Docker networking cần cẩn thận: ', 'Port mapping và container network cần cấu hình chính xác để P2P hoạt động đúng, đặc biệt khi peer cần kết nối đến nhau qua hostname.'),
    ('REST API + WebSocket là mô hình tốt cho chat: ', 'Kết hợp REST API (thao tác đồng bộ) + WebSocket (realtime events) tạo ra kiến trúc hiệu quả, dễ debug và mở rộng.'),
    ('Eventual consistency cần cân nhắc kỹ: ', 'Với DHT-lite và gossip protocol, cần xác định rõ các điều kiện conflict và cách giải quyết trước khi triển khai để tránh data inconsistency.'),
]
for bold_part, normal_part in lessons:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.space_after = Pt(4)
    rb = p.add_run(bold_part)
    rb.bold = True
    rb.font.size = Pt(12)
    rn = p.add_run(normal_part)
    rn.font.size = Pt(12)

add_section_divider(doc)
doc.add_page_break()

# ============================================================
# TÀI LIỆU THAM KHẢO
# ============================================================
add_heading(doc, 'TÀI LIỆU THAM KHẢO', 1)

references = [
    '[1]  Tanenbaum, A. S., & Van Steen, M. (2007). Distributed Systems: Principles and Paradigms (2nd ed.). Pearson Prentice Hall.',
    '[2]  Coulouris, G., Dollimore, J., Kindberg, T., & Blair, G. (2011). Distributed Systems: Concepts and Design (5th ed.). Addison-Wesley.',
    '[3]  Stoica, I., Morris, R., Karger, D., Kaashoek, M. F., & Balakrishnan, H. (2001). Chord: A scalable peer-to-peer lookup service for internet applications. ACM SIGCOMM Computer Communication Review, 31(4), 149–160.',
    '[4]  Maymounkov, P., & Mazières, D. (2002). Kademlia: A peer-to-peer information system based on the XOR metric. Lecture Notes in Computer Science, 2429, 53–65.',
    '[5]  Lamport, L. (1978). Time, clocks, and the ordering of events in a distributed system. Communications of the ACM, 21(7), 558–565.',
    '[6]  Thaler, D., & Ravishankar, C. V. (1998). Using name-based mappings to increase hit rates. IEEE/ACM Transactions on Networking, 6(1), 1–14. (Rendezvous Hashing / HRW)',
    '[7]  Javalin Framework Documentation. (2024). https://javalin.io/documentation',
    '[8]  React Documentation. (2024). https://reactjs.org/docs/getting-started.html',
    '[9]  Docker Documentation. (2024). https://docs.docker.com/',
    '[10] Tailscale Documentation. (2024). https://tailscale.com/kb/',
    '[11] SQLite Documentation. (2024). https://www.sqlite.org/docs.html',
    '[12] Java 17 API Documentation. (2024). https://docs.oracle.com/en/java/javase/17/docs/api/',
    '[13] NIST FIPS 180-4. (2015). Secure Hash Standard (SHS). National Institute of Standards and Technology.',
]

for ref in references:
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(5)
    p.paragraph_format.left_indent = Cm(1.0)
    p.paragraph_format.first_line_indent = Cm(-1.0)
    run = p.add_run(ref)
    run.font.size = Pt(12)
    run.font.name = 'Times New Roman'

# ============================================================
# LƯU FILE
# ============================================================
output_path = r'd:\DaiHoc\code\PhanTan\P2PChat\bao_cao.docx'
doc.save(output_path)
print(f'File saved: {output_path}')
print(f'Total pages estimated: ~62')
