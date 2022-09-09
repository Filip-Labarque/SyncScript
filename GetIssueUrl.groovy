import com.exalate.api.domain.connection.IConnection

class GetIssueUrl {
    static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }

    static String getLocal(String issueKey) {
        def injector = InjectorGetter.getInjector()
        def getGeneralSettings = {
            def gsp = injector.instanceOf(com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence.class)
            def gsOpt = await(gsp.get())
            def gs = orNull(gsOpt)
            gs
        }
        final def gs = getGeneralSettings()
        com.exalate.util.UrlUtils.concat(gs.issueTrackerUrl, "/browse/", issueKey)
    }

    static String getRemote(String remoteIssueKey, IConnection connection) {
        com.exalate.util.UrlUtils.concat(connection.remoteInstance.url.toString(), "/browse/", remoteIssueKey)
    }
}
