ALTER TABLE dinner_ingredients
    ADD COLUMN image_asset_id BIGINT NULL AFTER default_unit,
    ADD KEY idx_dinner_ingredients_image_asset (image_asset_id),
    ADD CONSTRAINT fk_dinner_ingredients_image_asset
        FOREIGN KEY (image_asset_id) REFERENCES dinner_image_assets (id);

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '油麦菜', '油麦菜', 'https://commons.wikimedia.org/wiki/File:Lactuca_sativa_var._angustana_''Karola''_kz01.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Lactuca_sativa_var._angustana_''Karola''_kz01.jpg', 'Kenraiz', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/', '2026-08-03', 'd63f0357854b679eea8d7381cf86564b0282539006113349a799b89d89b1b54b',
    3072, 4608, 'internal/ingredients/a-choy/original.jpg', 'media/ingredients/a-choy-list.webp', 'media/ingredients/a-choy-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/a-choy-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('油麦菜');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '牛肉', '牛肉', 'https://commons.wikimedia.org/wiki/File:Raw_beef.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Raw_beef.jpg', 'Halima Waziri', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/', '2026-08-03', '2c05d75e4cab3ca9b9ded1ff009433fc39dbf8b9aa277ae869021a52606dc4c1',
    2560, 1440, 'internal/ingredients/beef/original.jpg', 'media/ingredients/beef-list.webp', 'media/ingredients/beef-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/beef-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('牛肉');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '西兰花', '西兰花', 'https://commons.wikimedia.org/wiki/File:Broccoli_vegetable.jpg', 'https://upload.wikimedia.org/wikipedia/commons/9/98/Broccoli_vegetable.jpg', 'Jon Sullivan', 'Public domain', 'https://commons.wikimedia.org/wiki/Commons:Copyright_tags#Public_domain', '2026-08-03', 'cd6e2bd15458f1e88c8839d57dee1510d87bedce1e50600bca4f55e6d1e00941',
    1280, 960, 'internal/ingredients/broccoli/original.jpg', 'media/ingredients/broccoli-list.webp', 'media/ingredients/broccoli-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/broccoli-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('西兰花');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '鸡胸肉', '鸡胸肉', 'https://commons.wikimedia.org/wiki/File:Raw_chicken_slices.jpg', 'https://upload.wikimedia.org/wikipedia/commons/b/b8/Raw_chicken_slices.jpg', 'kakyusei', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '3423c813966713a7fdfe01726423110072395c2e19ab64e8708b9b52945a4d1a',
    3888, 2592, 'internal/ingredients/chicken-breast/original.jpg', 'media/ingredients/chicken-breast-list.webp', 'media/ingredients/chicken-breast-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/chicken-breast-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('鸡胸肉');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '鸡翅', '鸡翅', 'https://commons.wikimedia.org/wiki/File:Raw_chicken_wings.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Raw_chicken_wings.jpg', 'ProjectManhattan', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0/', '2026-08-03', '6200d4f88afd848e6afafec09fad2b71de5af06a91830408c8ecf8d052491c8e',
    1840, 2211, 'internal/ingredients/chicken-wings/original.jpg', 'media/ingredients/chicken-wings-list.webp', 'media/ingredients/chicken-wings-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/chicken-wings-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('鸡翅');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '鸡肉', '鸡肉', 'https://commons.wikimedia.org/wiki/File:Breast_chicken.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Breast_chicken.jpg', 'ReshmaNazeerhussain', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '9447ca4c934c179e32c61dbd030fb5df6d56d314e08b7f758e97ee4c0e98b5e1',
    960, 1280, 'internal/ingredients/chicken/original.jpg', 'media/ingredients/chicken-list.webp', 'media/ingredients/chicken-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/chicken-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('鸡肉');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '可乐', '可乐', 'https://commons.wikimedia.org/wiki/File:Glass_cola.jpg', 'https://upload.wikimedia.org/wikipedia/commons/1/10/Glass_cola.jpg', 'pic_p_ter', 'Public domain', 'https://commons.wikimedia.org/wiki/Commons:Copyright_tags#Public_domain', '2026-08-03', 'e66e4106c73dad20266f20ee76746996398090d2ab9bcdd2f2a7b466cda25def',
    2200, 1704, 'internal/ingredients/cola/original.jpg', 'media/ingredients/cola-list.webp', 'media/ingredients/cola-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/cola-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('可乐');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '食用油', '食用油', 'https://commons.wikimedia.org/wiki/File:Bottle_of_olive_oil.jpg', 'https://upload.wikimedia.org/wikipedia/commons/1/13/Bottle_of_olive_oil.jpg', 'margenauer', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', 'a82bbb115dea7e99c0eb2dd922497d9928af9cad951b9da45c6760d89dc366bb',
    3260, 3260, 'internal/ingredients/cooking-oil/original.jpg', 'media/ingredients/cooking-oil-list.webp', 'media/ingredients/cooking-oil-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/cooking-oil-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('食用油');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '黄瓜', '黄瓜', 'https://commons.wikimedia.org/wiki/File:Nice_cucumber.jpg', 'https://upload.wikimedia.org/wikipedia/commons/e/ed/Nice_cucumber.jpg', 'ProjectManhattan', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '49724fe79a3ce6279a110aab385dc87509188147e4729d3ca22f98d611ed60a3',
    2121, 3920, 'internal/ingredients/cucumber/original.jpg', 'media/ingredients/cucumber-list.webp', 'media/ingredients/cucumber-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/cucumber-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('黄瓜');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '鸡蛋', '鸡蛋', 'https://commons.wikimedia.org/wiki/File:Ten_chicken_eggs.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Ten_chicken_eggs.jpg', 'Achim55', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '736aed2f8af338c2d66863df317a6716a8fc16a8126c0e2c59a9e0eb1bf74249',
    2048, 1536, 'internal/ingredients/egg/original.jpg', 'media/ingredients/egg-list.webp', 'media/ingredients/egg-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/egg-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('鸡蛋');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '蒜', '蒜', 'https://commons.wikimedia.org/wiki/File:Garlic_cloves.jpg', 'https://upload.wikimedia.org/wikipedia/commons/f/f1/Garlic_cloves.jpg', 'Leon Brooks', 'Public domain', 'https://commons.wikimedia.org/wiki/Commons:Copyright_tags#Public_domain', '2026-08-03', '51b11a9982d0cafde2f32b68bd8c2edc84afc06030c415746675be1f3d59f006',
    3976, 2978, 'internal/ingredients/garlic/original.jpg', 'media/ingredients/garlic-list.webp', 'media/ingredients/garlic-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/garlic-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('蒜');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '姜', '姜', 'https://commons.wikimedia.org/wiki/File:Ginger_Root_(Zingiber_officinale).jpg', 'https://upload.wikimedia.org/wikipedia/commons/7/7f/Ginger_Root_%28Zingiber_officinale%29.jpg', 'Rubel Das', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '25f87b4cbbbcb812d558663c427c846d8cf1afb870866298be0480f7b2f8d119',
    2992, 2992, 'internal/ingredients/ginger/original.jpg', 'media/ingredients/ginger-list.webp', 'media/ingredients/ginger-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/ginger-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('姜');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '青椒', '青椒', 'https://commons.wikimedia.org/wiki/File:Green_Bell_Pepper_(53630132710).jpg', 'https://upload.wikimedia.org/wikipedia/commons/5/5a/Green_Bell_Pepper_%2853630132710%29.jpg', 'Alabama Extension', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '7b5159d34a539803286f67190f1d12e748deefdbc19c157fbf3c0158a041090e',
    5472, 3648, 'internal/ingredients/green-pepper/original.jpg', 'media/ingredients/green-pepper-list.webp', 'media/ingredients/green-pepper-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/green-pepper-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('青椒');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '面条', '面条', 'https://commons.wikimedia.org/wiki/File:%E6%99%92%E5%B9%B2%E7%9A%84%E6%89%8B%E5%B7%A5%E9%B2%9C%E9%9D%A2%E6%9D%A1.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/%E6%99%92%E5%B9%B2%E7%9A%84%E6%89%8B%E5%B7%A5%E9%B2%9C%E9%9D%A2%E6%9D%A1.jpg', 'Suginami', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '2b238510b26d9f61d74402a53fd425b145b844b5225400c55654155e980b8640',
    1706, 1279, 'internal/ingredients/noodles/original.jpg', 'media/ingredients/noodles-list.webp', 'media/ingredients/noodles-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/noodles-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('面条');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '猪肉', '猪肉', 'https://commons.wikimedia.org/wiki/File:Carne_de_Porco.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Carne_de_Porco.jpg', 'Stylledogheto', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '698e8617cade40c35bbe70838b7462738faa6fd10446695456c9569dfd194777',
    3120, 4160, 'internal/ingredients/pork/original.jpg', 'media/ingredients/pork-list.webp', 'media/ingredients/pork-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/pork-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('猪肉');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '土豆', '土豆', 'https://commons.wikimedia.org/wiki/File:Cut_Potatoes.jpg', 'https://upload.wikimedia.org/wikipedia/commons/2/25/Cut_Potatoes.jpg', 'Alabama Extension', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', 'eebe9cb54286f9be151e3db87c4b473d8515aabcbfbf6019cb979bb91a439c25',
    4476, 2769, 'internal/ingredients/potato/original.jpg', 'media/ingredients/potato-list.webp', 'media/ingredients/potato-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/potato-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('土豆');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '大米', '大米', 'https://commons.wikimedia.org/wiki/File:20191213_rice_grain_collection-1.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/20191213_rice_grain_collection-1.jpg', 'Balon Greyjoy', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '536dcb023ae8ff643fa02e122c70827e084706bce3478cadaa41cb559a729ce8',
    4020, 3185, 'internal/ingredients/rice/original.jpg', 'media/ingredients/rice-list.webp', 'media/ingredients/rice-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/rice-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('大米');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '盐', '盐', 'https://commons.wikimedia.org/wiki/File:Salt-crystals.jpg', 'https://upload.wikimedia.org/wikipedia/commons/9/97/Salt-crystals.jpg', 'Nate Steiner', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', 'e6f758cefcd7dc446e5c7420db82685d8be73d792f7fcd9af2b74a484354b1d9',
    2288, 1712, 'internal/ingredients/salt/original.jpg', 'media/ingredients/salt-list.webp', 'media/ingredients/salt-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/salt-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('盐');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '紫菜', '紫菜', 'https://commons.wikimedia.org/wiki/File:Dried_miyeok.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Dried_miyeok.jpg', 'freddy an', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '9d3cc71847377e0ca7225afd56aadda232d962dd8f6d06df695f8fffdf9e5b6d',
    1920, 1280, 'internal/ingredients/seaweed/original.jpg', 'media/ingredients/seaweed-list.webp', 'media/ingredients/seaweed-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/seaweed-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('紫菜');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '酱油', '生抽 酱油', 'https://commons.wikimedia.org/wiki/File:Bowl_of_soy_sauce.jpg', 'https://upload.wikimedia.org/wikipedia/commons/f/fa/Bowl_of_soy_sauce.jpg', 'Bodhi Peace', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0/', '2026-08-03', '0aac3e9181d57ffd43b9c71483be16b6c743dc4b8ec434230e7a1724a00bfba2',
    5152, 3864, 'internal/ingredients/soy-sauce/original.jpg', 'media/ingredients/soy-sauce-list.webp', 'media/ingredients/soy-sauce-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/soy-sauce-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('生抽', '酱油');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '葱', '葱', 'https://commons.wikimedia.org/wiki/File:Spring_Onion.jpg', 'https://upload.wikimedia.org/wikipedia/commons/9/9c/Spring_Onion.jpg', 'Donovan Govan', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0/', '2026-08-03', '0289118d773a7414f5c1af048e3bbb5b26b97b532e3b3f305567f7db8b9c1ba5',
    1988, 1259, 'internal/ingredients/spring-onion/original.jpg', 'media/ingredients/spring-onion-list.webp', 'media/ingredients/spring-onion-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/spring-onion-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('葱');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '糖', '糖', 'https://commons.wikimedia.org/wiki/File:A_Bowl_of_Sugar.jpg', 'https://upload.wikimedia.org/wikipedia/commons/1/12/A_Bowl_of_Sugar.jpg', 'Sparkveela', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '334840f0fc1aaf68c6eb0acdfa8874ec49c2001dcc060f84c2b5a632cee0b2a9',
    4000, 3000, 'internal/ingredients/sugar/original.jpg', 'media/ingredients/sugar-list.webp', 'media/ingredients/sugar-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/sugar-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('糖');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '番茄', '番茄', 'https://commons.wikimedia.org/wiki/File:Tomatoes.jpg', 'https://commons.wikimedia.org/wiki/Special:Redirect/file/Tomatoes.jpg', 'Wilfredor', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', 'a851c21371698ab57df141273c771599090abd6a8a65592255f067446fbedf2a',
    4165, 2681, 'internal/ingredients/tomato/original.jpg', 'media/ingredients/tomato-list.webp', 'media/ingredients/tomato-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/tomato-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('番茄');

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords, source_page_url, original_file_url,
    author, license_name, license_url, acquired_on, sha256, original_width,
    original_height, original_object_key, list_object_key, detail_object_key, status,
    reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '醋', '醋', 'https://commons.wikimedia.org/wiki/File:Brandy_Vinegar.jpg', 'https://upload.wikimedia.org/wikipedia/commons/4/47/Brandy_Vinegar.jpg', 'Brücke-Osteuropa', 'CC0 1.0', 'https://creativecommons.org/publicdomain/zero/1.0/', '2026-08-03', '2dae68a58f1d2bd0cf8b2f0213debaaf59a2d5269c41714ca0fe6ae65e0160fb',
    1692, 2766, 'internal/ingredients/vinegar/original.jpg', 'media/ingredients/vinegar-list.webp', 'media/ingredients/vinegar-detail.webp', 'APPROVED',
    CURRENT_TIMESTAMP(3)
);

UPDATE dinner_ingredients
SET image_asset_id = (
    SELECT id FROM dinner_image_assets
    WHERE list_object_key = 'media/ingredients/vinegar-list.webp'
    ORDER BY id DESC LIMIT 1
)
WHERE scope = 'SYSTEM' AND name IN ('醋');


