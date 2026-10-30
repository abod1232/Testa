override val mainPage = mainPageOf(
        "$mainUrl/main" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = baseUrl()
        // استبدال آمن فقط إذا كان الرابط يبدأ بـ mainUrl
        val fixedUrl = if (request.data.startsWith(mainUrl)) {
            request.data.replace(mainUrl, base)
        } else {
            request.data
        }

        val url = if (page > 1 && !fixedUrl.endsWith("/main")) {
            if (fixedUrl.contains("all_movies"))
                "${fixedUrl.removeSuffix("/")}/page/$page"
            else
                "${fixedUrl}/page/$page"
        } else {
            fixedUrl
        }

        val document = smartGet(url)
        val headers = getProtectedHeaders()
        if (fixedUrl.endsWith("/main") || fixedUrl == base || fixedUrl == mainUrl) {
            val lists = mutableListOf<HomePageList>()
            val sliderItems = document.select("#homeSlide .swiper-slide").mapNotNull {
                val slideHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val slideTitle = it.selectFirst(".h1 a")?.text()?.trim() ?: return@mapNotNull null
                val slidePoster = it.selectFirst(".poster img")?.attr("src")
                newMovieSearchResponse(slideTitle, slideHref, TvType.Movie) {
                    this.posterUrl = slidePoster
                    this.posterHeaders = headers
                }
            }
            if (sliderItems.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", sliderItems, isHorizontalImages = true))
            }
            document.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text()?.trim() ?: return@forEach
                val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResult() }

                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items))
                }
            }
            document.select("div.slider")
                .firstOrNull { it.selectFirst(".h4")?.text()?.contains("مشاهدة") == true }
                ?.let { mostWatchedBlock ->
                    val title = mostWatchedBlock.selectFirst(".h4")?.text()?.trim() ?: "الأكثر مشاهدة"
                    val items = mostWatchedBlock.select(".itemviews .postDiv").mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items, isHorizontalImages = true))
                    }
                }
            return newHomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
        } else {
            val items = document.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult() }
            val hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()
            return newHomePageResponse(request.name, items, hasNext)
        }
    }
