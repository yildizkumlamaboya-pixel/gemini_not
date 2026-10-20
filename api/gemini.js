// Vercel sunucu fonksiyonu: Gemini anahtarını gizli tutar.
// Vercel → Project → Settings → Environment Variables:
//   GEMINI_API_KEY = (Gemini anahtarın)
//   APP_CODE       = (isteğe bağlı) uygulamaya girilecek erişim kodu
module.exports = async (req, res) => {
  const KEY = process.env.GEMINI_API_KEY;
  const CODE = process.env.APP_CODE || '';

  if (req.method === 'GET') {
    return res.status(200).json({ ok: true, hasKey: !!KEY, needsCode: !!CODE });
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'Sadece POST' });
  if (!KEY) return res.status(500).json({ error: 'Sunucuda GEMINI_API_KEY tanımlı değil' });
  if (CODE && req.headers['x-app-code'] !== CODE) {
    return res.status(401).json({ error: 'Uygulama kodu hatalı (Ayarlar → Uygulama kodu)' });
  }

  let input = req.body;
  if (typeof input === 'string') { try { input = JSON.parse(input); } catch { input = {}; } }
  const { op, model, body } = input || {};
  const base = 'https://generativelanguage.googleapis.com/v1beta';

  try {
    let r;
    if (op === 'models') {
      r = await fetch(`${base}/models?pageSize=200`, { headers: { 'x-goog-api-key': KEY } });
    } else {
      if (!/^[a-z0-9.\-]+$/i.test(model || '')) return res.status(400).json({ error: 'Geçersiz model adı' });
      r = await fetch(`${base}/models/${model}:generateContent`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-goog-api-key': KEY },
        body: JSON.stringify(body || {}),
      });
    }
    const data = await r.json();
    return res.status(r.status).json(data);
  } catch (e) {
    return res.status(502).json({ error: 'Gemini\'ye ulaşılamadı: ' + e.message });
  }
};

module.exports.config = { api: { bodyParser: { sizeLimit: '4.5mb' } } };
