class InjectorGetter {


     static Object getInjector() {
                    try{
                        return play.api.Play$.MODULE$.current().injector()
                    } catch(e){
                      def context = com.exalate.replication.services.processor.CreateReplicaProcessor$.MODULE$.threadLocalContext.get()
                      if (!context) {
                         context = com.exalate.replication.services.processor.ChangeIssueProcessor$.MODULE$.threadLocalContext.get()
                      }
                      if (!context) {
                        context = com.exalate.replication.services.processor.CreateIssueProcessor$.MODULE$.threadLocalContext.get()
                      }
                      if (!context) {
                        throw new com.exalate.api.exception.IssueTrackerException(""" No context for executing external script CreateIssue.groovy. Please contact Exalate Support.""".toString())
                      }
                      context.injector
                    }
       }
}
