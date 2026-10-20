// Vercel sunucu fonksiyonu: Gemini anahtarını gizli tutar.
// Model yoğun/kotası dolu olursa sırayla yedek modellere geçer.
// Vercel → Settings → Environment Variables: GEMINI_API_KEY, (isteğe bağlı) APP_CODE
const FALLBACK = ['gemini-flash-latest', 'gemini-2.5-flash', 'gemini-flash-lite-latest', 'gemini-2.5-flash-lite', 'gemini-2.0-flash'];
const RETRY = new Set([429, 500, 502, 503, 504, 404]);
const sleep = ms => new Promise(r => setTimeout(r, ms));

module.exports = async (req, res) => {
  const KEY = process.env.GEMINI_API_KEY;
  const CODE = process.env.APP_CODE || '';

  if (req.method === 'GET') return res.status(200).json({ ok: true, hasKey: !!KEY, needsCode: !!CODE });
  if (req.method !== 'POST') return res.status(405).json({ error: 'Sadece POST' });
  if (!KEY) return res.status(500).json({ error: 'Sunucuda GEMINI_API_KEY tanımlı değil' });
  if (CODE && req.headers['x-app-code'] !== CODE) return res.status(401).json({ error: 'Uygulama kodu hatalı (Ayarlar → Uygulama kodu)' });

  let input = req.body;
  if (typeof input === 'string') { try { input = JSON.parse(input); } catch { input = {}; } }
  const { op, model, body } = input || {};
  const base = 'https://generativelanguage.googleapis.com/v1beta';

  try {
    if (op === 'models') {
      const r = await fetch(`${base}/models?pageSize=200`, { headers: { 'x-goog-api-key': KEY } });
      return res.status(r.status).json(await r.json());
    }
    const first = /^[a-z0-9.\-]+$/i.test(model || '') ? model : FALLBACK[0];
    const models = [first, ...FALLBACK.filter(m => m !== first)];
    let last = { status: 503, data: { error: { message: 'Gemini yanıt vermedi' } } };
    for (let i = 0; i < models.length; i++) {
      // İlk modeli bir kez daha dene (anlık yoğunluk çoğu zaman saniyeler içinde geçer)
      for (let attempt = 0; attempt < (i === 0 ? 2 : 1); attempt++) {
        const r = await fetch(`${base}/models/${models[i]}:generateContent`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'x-goog-api-key': KEY },
          body: JSON.stringify(body || {}),
        });
        const data = await r.json().catch(() => ({}));
        if (r.ok) { res.setHeader('x-gemini-model', models[i]); return res.status(200).json(data); }
        last = { status: r.status, data };
        if (!RETRY.has(r.status)) return res.status(r.status).json(data);
        await sleep(i === 0 && attempt === 0 ? 1200 : 300);
      }
    }
    return res.status(last.status).json(last.data);
  } catch (e) {
    return res.status(502).json({ error: 'Gemini\'ye ulaşılamadı: ' + e.message });
  }
};

module.exports.config = { api: { bodyParser: { sizeLimit: '4.5mb' } }, maxDuration: 60 };
