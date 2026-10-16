"""Gera PDFs de teste em português para validar extração, limpeza, capítulos e OCR.

Uso: python tools/make_test_pdf.py  ->  samples/livro-teste.pdf e samples/livro-teste-com-sumario.pdf
Texto original escrito para os testes (sem direitos de terceiros).
"""
import os
import pymupdf as fitz

TITLE = "O Farol de Areia Branca"
AUTHOR = "Autor de Teste"

CHAPTERS = [
    ("Capítulo 1", "A chegada", [
        "Quando o Sr. Antônio desceu do ônibus em Areia Branca, o vento já carregava o cheiro de sal e de chuva. Eram 18h30 de uma terça-feira, e a praça estava vazia, exceto por um cachorro que dormia sob o banco de madeira.",
        "Ele trazia apenas uma mala marrom e uma carta. A carta, assinada pela Dra. Helena Souza, dizia que o antigo farol precisava de um novo guardião e que a casa ao lado estaria disponível a partir do dia 3 de março de 1987.",
        "Antônio caminhou devagar pela rua principal. Contou 12 casas de um lado e 15 do outro, todas pintadas de azul ou de branco, como se a cidade inteira tivesse combinado esquecer as outras cores.",
        "No fim da rua, sobre as pedras, o farol erguia-se com uma dignidade cansada. A tinta descascava em placas compridas, e a lanterna do alto estava apagada havia meses, segundo lhe contaram depois.",
        "- Então o senhor é o novo guardião? - perguntou uma menina de tranças, sentada no muro. Antônio respondeu que sim, e ela riu como quem conhece um segredo que os adultos ainda não descobriram.",
    ]),
    ("Capítulo 2", "O livro de registros", [
        "Dentro da casa, sobre a mesa da cozinha, havia um livro de registros com capa de couro. As primeiras anotações eram de 1923 e descreviam, com letra caprichada, a passagem de navios, as tempestades e o preço do querosene.",
        "Antônio passou a noite lendo. Descobriu que o farol tivera sete guardiões, que o terceiro deles, o Prof. Joaquim, mantinha uma lista de aves marinhas, e que o quinto escrevia pequenos poemas nas margens das páginas.",
        "Na página nº 214 encontrou uma frase sublinhada duas vezes: \"a luz não é para quem chega, é para quem ainda está longe\". Ele leu a frase em voz alta, e a cozinha pareceu ficar um pouco mais quente.",
        "Pela manhã, decidiu que a primeira tarefa seria limpar a lanterna. Subiu os 96 degraus da escada em espiral, parando a cada patamar para olhar o mar, que mudava de cor conforme as nuvens passavam.",
        "Lá em cima, o mecanismo estava coberto de poeira e de sal. Antônio trabalhou durante horas, desmontando engrenagens, lubrificando eixos e anotando cada peça no caderno, para não esquecer a ordem de montagem.",
    ]),
    ("Capítulo 3", "A tempestade", [
        "A tempestade chegou numa sexta-feira, sem aviso. O rádio da capitania falava em ventos de 80 km/h e ondas de quatro metros, e recomendava que os barcos permanecessem no porto até segunda ordem.",
        "Mas o barco do pescador Raimundo ainda estava no mar. A esposa dele bateu à porta do farol com os olhos vermelhos, e Antônio entendeu, sem precisar de explicações, o que precisava ser feito naquela noite.",
        "Ele subiu os degraus de dois em dois. A lanterna, recém-montada, ainda não tinha sido testada. Antônio respirou fundo, girou a chave e esperou, contando os segundos como quem conta as batidas do próprio coração.",
        "A luz acendeu. Primeiro fraca, depois firme, varrendo o mar escuro em círculos regulares. Lá embaixo, na praia, algumas pessoas gritaram e acenaram, embora ninguém pudesse vê-las do alto.",
        "Às três da madrugada, o barco de Raimundo entrou no porto, guiado pela luz. No dia seguinte, a cidade inteira subiu a ladeira para agradecer, e a menina de tranças trouxe um bolo de fubá ainda morno.",
    ]),
    ("Capítulo 4", "O novo registro", [
        "Naquela noite, Antônio abriu o livro de registros e escreveu sua primeira anotação. Descreveu a tempestade, o barco, o bolo de fubá e, por fim, copiou a frase sublinhada que encontrara na página 214.",
        "Os anos passaram devagar, como passam as coisas que se repetem com cuidado. Antônio aprendeu o nome das aves, o humor das marés e a paciência necessária para manter acesa uma luz que quase ninguém agradece.",
        "Quando a menina de tranças cresceu, tornou-se engenheira e voltou a Areia Branca para modernizar o farol. Antônio mostrou a ela o livro de registros, e ela prometeu que a luz nunca mais ficaria apagada.",
        "E assim termina esta pequena história: com um farol aceso, um livro aberto sobre a mesa e o mar, lá fora, continuando a mudar de cor, como sempre fez e como sempre fará.",
    ]),
]

W, H = 340, 480  # formato de bolso, em pontos
MARGIN_X, TOP, BOTTOM = 48, 64, 70
BODY_FS = 11
LH = BODY_FS * 1.45
FONT = "tiro"  # Times-Roman
FONT_B = "tibo"


def text_len(s, fs=BODY_FS, font=FONT):
    return fitz.get_text_length(s, fontname=font, fontsize=fs)


class Writer:
    def __init__(self, doc):
        self.doc = doc
        self.page = None
        self.y = 0
        self.pages_with_header = []

    def new_page(self, header=True):
        self.page = self.doc.new_page(width=W, height=H)
        self.y = TOP
        if header:
            self.pages_with_header.append(self.page.number)
        return self.page

    def ensure(self, h):
        if self.y + h > H - BOTTOM:
            self.new_page()

    def paragraph(self, text):
        words = text.split()
        maxw = W - 2 * MARGIN_X
        indent = BODY_FS * 1.5
        line, lines = [], []
        width = maxw - indent
        i = 0
        while i < len(words):
            w = words[i]
            cand = " ".join(line + [w])
            if text_len(cand) <= width:
                line.append(w)
                i += 1
                continue
            # hifenização simples de palavras longas no fim da linha
            if len(w) >= 9 and w.isalpha():
                for cut in range(len(w) - 3, 3, -1):
                    part = w[:cut] + "-"
                    if text_len(" ".join(line + [part])) <= width:
                        line.append(part)
                        words[i] = w[cut:]
                        break
            lines.append(line)
            line = []
            width = maxw
        if line:
            lines.append(line)
        for li, ln in enumerate(lines):
            self.ensure(LH)
            x = MARGIN_X + (indent if li == 0 else 0)
            self.page.insert_text((x, self.y), " ".join(ln), fontname=FONT, fontsize=BODY_FS)
            self.y += LH
        self.y += 2

    def heading(self, label, title):
        self.new_page()
        self.y = TOP + 50
        self.page.insert_text((MARGIN_X, self.y), label, fontname=FONT_B, fontsize=20)
        self.y += 28
        self.page.insert_text((MARGIN_X, self.y), title, fontname=FONT_B, fontsize=20)
        self.y += 40


def decorate(doc, writer, skip):
    for pno in range(len(doc)):
        if pno in skip:
            continue
        p = doc[pno]
        p.insert_text((MARGIN_X, 36), TITLE.upper(), fontname=FONT, fontsize=8)
        num = str(pno + 1)
        p.insert_text(((W - text_len(num, 9)) / 2, H - 30), num, fontname=FONT, fontsize=9)


def build(with_outline, path):
    doc = fitz.open()
    wr = Writer(doc)
    # Folha de rosto
    p = wr.new_page(header=False)
    p.insert_text((MARGIN_X, 200), TITLE, fontname=FONT_B, fontsize=24)
    p.insert_text((MARGIN_X, 240), AUTHOR, fontname=FONT, fontsize=14)
    # Sumário (não deve ser narrado)
    p = wr.new_page(header=False)
    p.insert_text((MARGIN_X, TOP + 20), "Sumário", fontname=FONT_B, fontsize=16)
    y = TOP + 60
    toc_pages = [4, 6, 8, 10]
    for (label, title, _), pg in zip(CHAPTERS, toc_pages):
        entry = f"{label} - {title}"
        dots = " " + "." * int((W - 2 * MARGIN_X - text_len(entry) - 30) / text_len(".")) + " "
        p.insert_text((MARGIN_X, y), entry + dots + str(pg), fontname=FONT, fontsize=BODY_FS)
        y += LH * 1.4
    chapter_pages = []
    for ci, (label, title, paras) in enumerate(CHAPTERS):
        wr.heading(label, title)
        chapter_pages.append(wr.page.number)
        for k, para in enumerate(paras):
            wr.paragraph(para)
            if ci == 1 and k == 2:
                # nota de rodapé na página atual
                wr.page.insert_text((MARGIN_X, H - 52), "1 Registro mantido pela capitania entre 1923 e 1987.", fontname=FONT, fontsize=8)
    # Página digitalizada: texto renderizado como imagem, sem camada de texto (exige OCR).
    tmp = fitz.open()
    tp = tmp.new_page(width=W, height=H)
    tp.insert_text((MARGIN_X, TOP + 20), "Posfácio", fontname=FONT_B, fontsize=18)
    yy = TOP + 60
    for line in [
        "Esta página foi digitalizada a partir de um papel",
        "antigo e não possui texto selecionável. O aplicativo",
        "deve reconhecer estas linhas com OCR local e narrar",
        "o conteúdo normalmente, sem internet.",
    ]:
        tp.insert_text((MARGIN_X, yy), line, fontname=FONT, fontsize=13)
        yy += 20
    pix = tp.get_pixmap(dpi=200)
    scan = doc.new_page(width=W, height=H)
    scan.insert_image(scan.rect, pixmap=pix)
    scan_no = scan.number
    decorate(doc, wr, skip={0, 1, scan_no})
    if with_outline:
        toc = [[1, f"{label}: {title}", pg + 1] for (label, title, _), pg in zip(CHAPTERS, chapter_pages)]
        toc.append([1, "Posfácio", scan_no + 1])
        doc.set_toc(toc)
    doc.set_metadata({"title": TITLE, "author": AUTHOR})
    doc.save(path)
    print(path, len(doc), "páginas; capítulos começam em", [p + 1 for p in chapter_pages], "; digitalizada:", scan_no + 1)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "samples")
    os.makedirs(out, exist_ok=True)
    build(False, os.path.join(out, "livro-teste.pdf"))
    build(True, os.path.join(out, "livro-teste-com-sumario.pdf"))


def build_long(path, chapters=24, repeat=6):
    """Livro longo (~200 páginas) para testar desempenho, memória, cancelamento e retomada."""
    doc = fitz.open()
    wr = Writer(doc)
    p = wr.new_page(header=False)
    p.insert_text((MARGIN_X, 200), "Livro Longo de Teste", fontname=FONT_B, fontsize=22)
    for c in range(chapters):
        base = CHAPTERS[c % len(CHAPTERS)]
        wr.heading(f"Capítulo {c + 1}", base[1])
        for r in range(repeat):
            for para in base[2]:
                wr.paragraph(para)
    decorate(doc, wr, skip={0})
    doc.save(path)
    print(path, len(doc), "páginas")


if __name__ == "__main__":
    build_long(os.path.join(os.path.dirname(__file__), "..", "samples", "livro-longo.pdf"))
