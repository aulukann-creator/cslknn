"""Çalıştırma: python -m unittest discover -s tools -p 'test_*.py' -v"""
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import detect
import engine


class SelectorTests(unittest.TestCase):
    def test_empty_alt_falls_back_to_accessible_title(self):
        doc = engine.parse_doc('<a aria-label="Dizi"><img alt=""></a>', 'html', 'https://example.com')
        self.assertEqual(engine.select(doc, 'img@alt || a@aria-label', 'https://example.com'), ['Dizi'])

    def test_script_embed_is_found_without_executing_javascript(self):
        text = '<script>self.__next_f.push([1,"src:\\"https://player.example/iframe.php?v=123\\""])</script>'
        doc = engine.parse_doc(text, 'html', 'https://example.com')
        self.assertEqual(engine.select(doc, detect._html_links()['src'], 'https://example.com'),
                         ['https://player.example/iframe.php?v=123'])

    def test_series_cards_and_json_ld_episodes_are_detected(self):
        html = ''.join(f'<a href="/dizi/show-{i}" aria-label="Show {i}"><img alt=""></a>' for i in range(4))
        series = {'@type': 'TVSeries', 'name': 'Show', 'containsSeason': [
            {'episode': [{'name': 'Pilot', 'episodeNumber': 1,
                          'url': 'https://example.com/show-2-sezon-1-bolum'}]}]}
        response = MagicMock(text='<script type="application/ld+json">' + json.dumps(series) + '</script>')
        with patch.object(detect, 'get', return_value=response):
            cfg = detect.detect_html('https://example.com', html)
        self.assertEqual(cfg['types'], ['TvSeries'])
        doc = engine.parse_doc(response.text, 'ld_json', 'https://example.com')
        root = engine.items_of(doc, cfg['detail']['root'])[0]
        ep = engine.items_of(root, cfg['detail']['episodes']['items'])[0]
        self.assertEqual(engine.select_one(ep, cfg['detail']['episodes']['season'], 'https://example.com'), '2')


class DetectionWriteTests(unittest.TestCase):
    def run_detection(self, folder, ok, dry):
        cfg = {'_pattern': 'html', 'mainUrl': 'https://example.com', 'home': [], 'list': {}}
        args = ['detect.py', 'example.com', '--name', 'Example', '--id', 'example', '--out', folder]
        if dry:
            args.append('--dry')
        with patch('sys.argv', args), patch.object(detect, 'detect', return_value=cfg), \
                patch.object(engine, 'test', return_value=ok), patch.dict(os.environ, {'GITHUB_OUTPUT': ''}), \
                contextlib.redirect_stdout(io.StringIO()):
            detect.main()

    def test_dry_run_preserves_existing_recipe(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, 'example.json')
            path.write_text('original', encoding='utf-8')
            self.run_detection(folder, True, True)
            self.assertEqual(path.read_text(encoding='utf-8'), 'original')

    def test_failed_dry_run_returns_error_and_preserves_recipe(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, 'example.json')
            path.write_text('original', encoding='utf-8')
            with self.assertRaises(SystemExit) as raised:
                self.run_detection(folder, False, True)
            self.assertEqual(raised.exception.code, 1)
            self.assertEqual(path.read_text(encoding='utf-8'), 'original')

    def test_failed_replacement_preserves_existing_recipe(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, 'example.json')
            path.write_text('original', encoding='utf-8')
            with self.assertRaises(SystemExit):
                self.run_detection(folder, False, False)
            self.assertEqual(path.read_text(encoding='utf-8'), 'original')


class PlaybackTests(unittest.TestCase):
    def run_recipe_test(self, *, homes=True, kind='movie', status=200):
        src = {'id': 'test', 'name': 'Test', 'mainUrl': 'https://example.com',
               'home': [{'title': 'Home', 'url': 'https://example.com'}] if homes else [],
               'links': {'referer': 'https://example.com/watch'}}
        fake = MagicMock()
        fake.base = src['mainUrl']
        fake.home.return_value = ([engine.Item(title='Film', url='https://example.com/film')], False)
        fake.load.return_value = {'title': 'Film', 'kind': kind, 'episodes': [], 'data': 'video'}
        fake.links.return_value = [{'url': 'https://example.com/video.mp4', 'via': 'media'}]
        response = fake.f.s.get.return_value.__enter__.return_value
        response.status_code, response.ok = status, status < 400
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, 'recipe.json')
            path.write_text(json.dumps(src), encoding='utf-8')
            with patch.object(engine, 'Engine', return_value=fake), contextlib.redirect_stdout(io.StringIO()):
                result = engine.test(str(path))
        return result, fake

    def test_media_403_fails_and_closes_response(self):
        result, fake = self.run_recipe_test(status=403)
        self.assertFalse(result)
        self.assertEqual(fake.f.s.get.call_args.kwargs['headers']['Referer'], 'https://example.com/watch')
        fake.f.s.get.return_value.__exit__.assert_called_once()

    def test_media_200_passes(self):
        self.assertTrue(self.run_recipe_test()[0])

    def test_series_without_episodes_fails(self):
        self.assertFalse(self.run_recipe_test(kind='series')[0])

    def test_no_home_is_not_a_success(self):
        self.assertFalse(self.run_recipe_test(homes=False)[0])


if __name__ == '__main__':
    unittest.main()
